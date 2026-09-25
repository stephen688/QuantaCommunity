# M5-A 熔断三态 实施计划

> **For agentic workers:** 本计划按 Task 顺序执行；Task 内「先完整实现 → 末尾统一批量测试」为定制节拍（见 Global Constraints）。步骤用 checkbox（`- [ ]`）跟踪。

**Goal:** 落地三域三态熔断（llm / memory / main_service，closed→open→half_open），LLM 熔断静默不回、记忆域熔断降级照常回复、主服务熔断快速失败。

**Architecture:** 纯逻辑状态机 `crosscutting/breaker.py`（进程内存、注入时钟），管线在真实调用成败处记账（record_success/record_failure），open 时不敲已熔断依赖直接走既定分叉。三域划分承接 PRD F6「LLM/审核/记忆」的 C-6 改形：审核档由 main_service（写库=审核入口）承接。

**Tech Stack:** Python 3.12 + asyncio（无新依赖）。

**Spec:** `QuantaBot/总计划.md` §2 M5 节 + 本文件「需求结论」。执行前先读 `QuantaBot/AGENTS.md` §0/§4/§5。

---

## 需求结论（grill-me 两轮对齐，2026-09-25）

1. **三份熔断**：`llm`（决策+生成）/ `memory`（记忆读写+RAG 同域，共享 Qdrant+embedding）/ `main_service`（评论树+写库；写库=审核入口，承接 PRD「审核」档的 C-6 改形）。
2. **open 行为分通道**：LLM open→静默不回（新决策枚举 `failed_breaker`）；memory open→降级继续回复（无记忆/无引用，trace 标记 `memory_degraded`）；main_service open→`failed` 不回（写不进去回了也白回，拉取前快速失败）。
3. **转换参数**（全部 Settings 可配）：连续失败 **5 次**进 open（成功一次清零；超时计入失败）；open 保持 **llm=60s / memory=30s / main_service=30s**；half_open=放行下一条**真实触发**当探测（单消费者串行，无并发探测问题，不造探测流量）——探测成功→closed+清零，失败→立即回 open 重置冷却。
4. **状态存进程内存**：单实例架构；重启=closed 重新计数（可接受）；不存 Redis（不给熔断器自己引入新故障域）。
5. **摘要调用边界**：远区摘要失败已有 M3 降级闭环（丢远区照常回复），不计入熔断计数；LLM 整体故障时决策调用先行失败，摘要天然不会被执行。
6. **Qdrant 补超时档**：`qdrant_timeout_seconds=5.0`（记忆读写快失败是熔断计数有意义的的前提；当前用 SDK 默认值过长）。

## Global Constraints

- **任务粒度（用户 2026-09-25 定制，覆盖默认细粒度节拍）**：大块 Task，Task 内先写完整实现再统一批量测试；不写一点点就测。
- **测试纪律**：只写核心链路测试（状态机转换、管线分叉、闸门行为），不写参数化琐碎小测；测试断言真实行为，禁止 mock 审核幂等伪装通过。
- 分层硬规则：`pipeline/memory → crosscutting → infra`；外部客户端只在 `composition.py` 创建注入；配置只从 `infra/settings.py` 读。
- 中文注释；核心业务步骤行内注释；命名禁单字母；模块头注释含职责/边界/已知坑。
- git 在 `QuantaCommunity` 根仓库执行，路径带 `QuantaBot/` 前缀；Conventional Commits；横切改动（熔断）提交信息注明影响面。
- 执行顺序：**A → B → C → D**（B/C/D 的管线 diff 均基于 A 合入后的状态）。
- 每个 Task 完成后必跑：`uv run ruff format src tests` + `uv run ruff check src tests` + `uv run pytest tests/unit -q`。

## 文件结构

- Create: `src/quanta_bot/crosscutting/breaker.py`（状态机+三域注册表）
- Create: `tests/unit/crosscutting/test_breaker.py`
- Create: `eval/cases/pipeline-breaker-llm.yaml`
- Modify: `src/quanta_bot/infra/settings.py`（熔断参数 + qdrant 超时）
- Modify: `src/quanta_bot/crosscutting/ports.py`（Decision 增 `failed_breaker`）
- Modify: `src/quanta_bot/pipeline/ports.py`（RunTrace 增 `memory_degraded`）
- Modify: `src/quanta_bot/pipeline/pipeline.py`（三域接线）
- Modify: `src/quanta_bot/composition.py`（注册表装配 + qdrant timeout）
- Modify: `tests/unit/pipeline/test_pipeline.py`（追加 4 个核心测试）
- Modify: `tests/eval/_runner.py`（EvalCase 增 `breakers_open`）
- Modify: `eval/gate-manifest.yaml`（fast_gate_ids 增本计划 case）
- Modify: `.env.example`（新配置项）

---

### Task 1: 熔断器实现 + 三域管线接线 + 装配（一次性完整实现）

**Files:** 见「文件结构」Create/Modify 列表（除测试与 eval 外全部）。

**Interfaces（Produces，后续 Task 与 B/C/D 依赖）:**
- `crosscutting/breaker.py`：`BreakerState`（StrEnum: closed/open/half_open）、`CircuitBreaker(name, failure_threshold=5, open_seconds=60.0, clock=time.monotonic)`（方法 `allow_request() -> bool`、`record_success()`、`record_failure()`；属性 `state`、`failure_count`）、`BreakerRegistry`（dataclass，字段 `llm`/`memory`/`main_service`，默认档=共识值 5/60/30/30）。
- `PipelineDeps.breakers: BreakerRegistry`（默认 `field(default_factory=BreakerRegistry)`——既有测试/eval runner 组装零改动）。
- `Decision` 枚举新增 `"failed_breaker"`；`RunTrace.memory_degraded: bool = False`。

- [ ] **Step 1.1: settings.py 增配置**

在 `infra/settings.py` 的超时档注释块（L72-76 附近）之后追加：

```python
    # ---- M5 熔断三态（closed→open→half_open；连续失败计数与 open 冷却按依赖分档）----
    # 连续失败次数进 open（成功一次清零；超时计入失败——AGENTS Do「三态熔断，超时静默不回」）
    breaker_failure_threshold: int = 5
    breaker_llm_open_seconds: float = 60.0  # LLM 熔断冷却放长（防雪崩）
    breaker_memory_open_seconds: float = 30.0  # 记忆/RAG 域（单次调用便宜，恢复探测代价低）
    breaker_main_service_open_seconds: float = 30.0  # 主服务（评论树+写库）域
    # Qdrant 客户端超时（记忆读写快失败的前提——SDK 默认值过长；M2 起 AsyncQdrantClient 一直用默认）
    qdrant_timeout_seconds: float = 5.0
```

同步在 `.env.example` 末尾追加对应条目（带中文注释，格式随现有文件）。

- [ ] **Step 1.2: 新建 crosscutting/breaker.py（完整文件）**

```python
"""crosscutting/breaker —— 三态熔断器（M5-熔断三态：closed→open→half_open）。

职责：单依赖域的失败计数与三态转换（纯逻辑，进程内存——单实例架构；重启=closed 重新计数）；
      half_open 语义=放行下一条真实请求当探测（单消费者串行，天然无并发探测，不造探测流量）。
边界：不做重试、不发探测流量、不持久化（存 Redis 会给熔断器自己引入新故障域）；
      谁调用谁记账——record_success/record_failure 由调用方（pipeline）在真实调用成败后调用。
键口径：三份实例=llm / memory（记忆+RAG 同域）/ main_service（评论树+写库，承接 PRD「审核」档
      的 C-6 改形：写库入口即审核入口）。参数共识（grill 2026-09-25）：阈值 5，冷却 60/30/30s。
已知坑：时间源经 clock 参数注入（测试用假时钟推进，不睡真时间）；状态推进惰性——冷却是否到点
      由 allow_request 判断时推进（state 属性只读，不主动转 half_open）。
"""

import time
from collections.abc import Callable
from dataclasses import dataclass, field
from enum import StrEnum


class BreakerState(StrEnum):
    """熔断三态（closed 正常 / open 熔断拒绝 / half_open 放行单次探测）。"""

    CLOSED = "closed"
    OPEN = "open"
    HALF_OPEN = "half_open"


class CircuitBreaker:
    """单依赖域熔断器（纯同步逻辑；单消费者串行架构无并发写，不设锁）。"""

    def __init__(
        self,
        name: str,
        failure_threshold: int = 5,
        open_seconds: float = 60.0,
        clock: Callable[[], float] = time.monotonic,
    ) -> None:
        self.name = name
        self._failure_threshold = failure_threshold
        self._open_seconds = open_seconds
        self._clock = clock
        self._state = BreakerState.CLOSED
        self._failure_count = 0
        self._opened_at = 0.0

    @property
    def state(self) -> BreakerState:
        """当前态（只读视图；open→half_open 由 allow_request 到点惰性推进）。"""
        return self._state

    @property
    def failure_count(self) -> int:
        """closed 态连续失败计数（open/half_open 归零）。"""
        return self._failure_count

    def allow_request(self) -> bool:
        """是否放行本次请求：closed=True；open 未到冷却=False；open 到冷却=转 half_open 放行探测。"""
        if self._state == BreakerState.OPEN:
            if self._clock() - self._opened_at < self._open_seconds:
                return False  # 冷却未到：熔断拒绝（不敲已熔断依赖）
            self._state = BreakerState.HALF_OPEN  # 到点：放行下一条真实请求当探测
        return True

    def record_success(self) -> None:
        """成功记账：half_open→closed（恢复）；closed 清零连续失败计数。"""
        self._state = BreakerState.CLOSED
        self._failure_count = 0

    def record_failure(self) -> None:
        """失败记账：closed 计数到阈值进 open；half_open 探测失败立即回 open（冷却重置）。"""
        if self._state == BreakerState.HALF_OPEN:
            self._trip_open()  # 探测失败：直接回 open，重新冷却
            return
        self._failure_count += 1
        if self._failure_count >= self._failure_threshold:
            self._trip_open()

    def _trip_open(self) -> None:
        """进入 open 态并重置冷却起点与计数。"""
        self._state = BreakerState.OPEN
        self._opened_at = self._clock()
        self._failure_count = 0


@dataclass
class BreakerRegistry:
    """三域熔断注册表（pipeline 消费；composition 按 Settings 构建，测试默认档直接默认构造）。

    默认档=grill 共识值：阈值 5；冷却 llm=60s / memory=30s / main_service=30s。
    """

    llm: CircuitBreaker = field(
        default_factory=lambda: CircuitBreaker("llm", open_seconds=60.0)
    )
    memory: CircuitBreaker = field(
        default_factory=lambda: CircuitBreaker("memory", open_seconds=30.0)
    )
    main_service: CircuitBreaker = field(
        default_factory=lambda: CircuitBreaker("main_service", open_seconds=30.0)
    )
```

- [ ] **Step 1.3: ports 扩展**

`crosscutting/ports.py` 的 `Decision` Literal 增加（放在 `"failed"` 之前）：

```python
    "failed_breaker",  # LLM 熔断 open，静默不回（M5-A；快速失败不敲已熔断依赖）
```

`pipeline/ports.py` 的 `RunTrace` 末尾增加（`leak_hits` 之后）：

```python
    memory_degraded: bool = False  # 记忆域熔断/异常降级标记（照常回复但无记忆注入——M5-A）
```

- [ ] **Step 1.4: pipeline.py 三域接线**

修改点如下（基于当前 main 状态逐处替换）：

① import 区（`from quanta_bot.crosscutting import budget, ...` 之后）加：

```python
from quanta_bot.crosscutting.breaker import BreakerRegistry
```

`from dataclasses import dataclass` 改为 `from dataclasses import dataclass, field`。

② `PipelineDeps` 末尾（`leak_extra_patterns` 之后）加：

```python
    # M5-A 熔断三域（llm / memory / main_service；composition 按 Settings 构建，测试默认档直接默认构造）
    breakers: BreakerRegistry = field(default_factory=BreakerRegistry)
```

③ `_Outcome` 末尾加：

```python
    memory_degraded: bool = False  # 记忆域降级标记（M5-A）
```

④ `run()` 中 RunTrace 构造加字段（`leak_hits=outcome.leak_hits,` 之后）：

```python
            memory_degraded=outcome.memory_degraded,
```

⑤ `_execute` ⑤ 拉取块——拉取前加 main_service 快速失败守卫，拉取成功后记账：

```python
    try:
        # ⑤ 拉取现场（C-2①③ 线程 + C-2② 全量楼层）——main_service 熔断 open 时快速失败不敲已熔断依赖
        if not deps.breakers.main_service.allow_request():
            return _Outcome("failed", "main_service 熔断 open，静默不回")
        thread = await deps.comment_tree.fetch_context(event)
        floors = context.filter_floors_at_waterline(
            event,
            thread,
            await deps.comment_tree.fetch_floors(event.post_id),
        )  # ⑤ 水位过滤：触发后的楼层不得进入本次决策
        deps.breakers.main_service.record_success()  # 拉取成功记账（写库另有记账）
```

⑥ ⑥ 记忆召回块整块替换为（熔断 open 跳过 + 异常记账降级）：

```python
        # ⑥ 记忆粗召回（向量管"找得到"；负面 feedback 全量并行取）——记忆是可降级通道：
        # 熔断 open→跳过降级；异常→计数+降级为空候选 WARNING 留痕照常回复（真存储故障不阻断回复链路）
        persona_version = deps.persona.persona_version
        memory_degraded = False
        if not deps.breakers.memory.allow_request():
            logger.warning("记忆熔断 open，跳过召回（降级照常回复）")
            memory_degraded = True
            candidates, negatives = (), ()
        else:
            try:
                candidates = await deps.memory_store.recall(
                    event.commenter_user_id, persona_version, event.content, deps.memory_recall_top_k
                )
                negatives = await deps.memory_store.negative_feedback(
                    event.commenter_user_id, persona_version
                )
                deps.breakers.memory.record_success()  # 记忆读成功记账
            except Exception as exc:  # 记忆读失败降级（计数后照常回复）
                deps.breakers.memory.record_failure()  # 失败记账（连续 5 次进 open）
                logger.warning("记忆召回失败降级为空候选（不阻断回复）：%s", exc)
                candidates, negatives = (), ()
                memory_degraded = True
```

⑦ ⑦ 决策前加 LLM 熔断守卫（在 `nearby_digest = ...` 之前）：

```python
        # ⑦ 决策一车四用（LLM 熔断 open→静默不回；失败记账走大 except 按 LLMClientError 归 llm 域）
        if not deps.breakers.llm.allow_request():
            return _Outcome("failed_breaker", "LLM 熔断 open，静默不回")
```

⑧ ⑧ 检索块：`if decision_result.need_retrieval:` 分支改为三分叉（未配置 / 熔断 / 正常）：

```python
        if decision_result.need_retrieval:
            if deps.retriever is None:
                retrieval_degraded = True  # 检索未配置：降级直说不知道（生成侧 prompt 已含）
            elif not deps.breakers.memory.allow_request():
                # 检索与记忆同域熔断（Qdrant+embedding 共享）——降级留痕照常回复
                logger.warning("检索熔断 open，降级直说不知道（不阻断回复）")
                retrieval_degraded = True
            else:
                try:
                    fragments = await deps.retriever.retrieve(
                        event.content, limit=deps.rag_fragment_limit
                    )
                    deps.breakers.memory.record_success()  # 检索成功记账
                except Exception as exc:  # 检索失败=计数+降级不阻断（与未配置同口径）
                    deps.breakers.memory.record_failure()  # 失败记账
                    logger.warning("RAG 检索失败降级（不阻断回复）：%s", exc)
                    retrieval_degraded = True
                else:
                    for fragment in fragments:  # 渲染行（来源标注随行——trace 对质用）
                        line = f"【检索|{fragment.doc_kind}】{fragment.content}（来源：{fragment.source}）"
                        if len("\n".join([*retrieval_lines, line])) > RESERVED_BUDGET:
                            retrieval_trunc = (
                                *retrieval_trunc,
                                TruncationRecord(
                                    channel="B",
                                    what=f"检索片段（来源：{fragment.source}）",
                                    reason="超预留预算丢末位",
                                    chars_dropped=len(line),
                                ),
                            )
                            break
                        retrieval_lines.append(line)
```

⑨ ⑩ 生成调用后加 LLM 成功记账（`output = await generation.generate(...)` 之后一行）：

```python
        deps.breakers.llm.record_success()  # 决策+生成均成功（LLM 域恢复信号）
```

⑩ ⑪ 写库后加 main_service 成功记账（`await deps.reply_writer.write_reply(output.reply)` 之后一行）：

```python
        deps.breakers.main_service.record_success()  # 写库成功记账（拉取已各自记账）
```

⑪ 大 except 改为按异常类型记账（替换原 `except (...) as exc:` 块开头）：

```python
    except (CommentFetchError, LLMClientError, ReplyWriteError) as exc:
        # 已知失败类型静默不回（红线 §0.3）+ 按异常类型记账到对应熔断域（评论树/写库同域 main_service）
        if isinstance(exc, LLMClientError):
            deps.breakers.llm.record_failure()
        else:
            deps.breakers.main_service.record_failure()
        # 决策已成功时携带 mode 归因（M2 口径保持）。
        # 2026-09-17 review I-1：拉取异常原裸逃 _execute 击穿 run() 单出口（无 RunTrace/
        # 无归因，consumer 兜底丢观测）——HTTPCommentTreeFetcher 现统一包装 CommentFetchError。
        return _Outcome(...)
```

（`return _Outcome(...)` 内容不变。）

⑫ ⑫ 记忆落库块改为带熔断守卫（`if decision_result.memory_ops:` 内部）：

```python
    if decision_result.memory_ops:
        if not deps.breakers.memory.allow_request():
            logger.warning("记忆熔断 open，跳过记忆落库（回复已成功，不阻断）")
        else:
            try:
                await deps.memory_store.apply_ops(
                    event.commenter_user_id, persona_version, decision_result.memory_ops
                )
                deps.breakers.memory.record_success()  # 记忆写成功记账
            except Exception as exc:  # 记忆失败不回滚回复（观测 WARNING + 计数）
                deps.breakers.memory.record_failure()  # 失败记账
                logger.warning("记忆四态落库失败（不阻断回复）：%s", exc)
```

⑬ replied 结尾 `_Outcome(...)` 加字段（`leak_hits=leak_hits,` 之后）：

```python
        memory_degraded=memory_degraded,
```

⑭ 模块头 docstring 边界行更新：`熔断/频率/消费暂停不在本层（M5/Tranche B）` 改为 `频率/成本分档不在本层（M5-B/C）；熔断三态已落地（M5-A）——摘要调用失败已有降级闭环不计入熔断计数`。

- [ ] **Step 1.5: composition.py 装配**

① import 区加：

```python
from quanta_bot.crosscutting.breaker import BreakerRegistry, CircuitBreaker
```

② qdrant 客户端构建加 timeout（`AsyncQdrantClient(url=settings.qdrant_url)` 处）：

```python
        qdrant_client = AsyncQdrantClient(
            url=settings.qdrant_url, timeout=settings.qdrant_timeout_seconds
        )  # M5-A：记忆读写快失败的超时档（若已装 SDK 版本不支持 timeout 形参，查签名后以等价参数实现并在此注记）
```

③ `deps = PipelineDeps(` 之前构建注册表，构造参数里注入：

```python
    # M5-A 三域熔断注册表（阈值/冷却从 Settings 注入；进程内存态）
    breakers = BreakerRegistry(
        llm=CircuitBreaker(
            "llm",
            failure_threshold=settings.breaker_failure_threshold,
            open_seconds=settings.breaker_llm_open_seconds,
        ),
        memory=CircuitBreaker(
            "memory",
            failure_threshold=settings.breaker_failure_threshold,
            open_seconds=settings.breaker_memory_open_seconds,
        ),
        main_service=CircuitBreaker(
            "main_service",
            failure_threshold=settings.breaker_failure_threshold,
            open_seconds=settings.breaker_main_service_open_seconds,
        ),
    )
```

`PipelineDeps(...)` 参数加 `breakers=breakers,`。

- [ ] **Step 1.6: 快速自检（不写测试，只确认可导入）**

Run: `uv run python -c "from quanta_bot.composition import build_runtime; from quanta_bot.infra.settings import Settings; build_runtime(Settings())"`
Expected: 无异常退出（fake 模式装配含默认注册表）。

---

### Task 2: 批量核心测试 + eval case + 门禁接线

**Files:**
- Create: `tests/unit/crosscutting/test_breaker.py`
- Create: `eval/cases/pipeline-breaker-llm.yaml`
- Modify: `tests/unit/pipeline/test_pipeline.py`（追加）
- Modify: `tests/eval/_runner.py`（EvalCase + run_case 预置逻辑）
- Modify: `eval/gate-manifest.yaml`（fast_gate_ids）

**Interfaces（Consumes）:** Task 1 的 `BreakerRegistry`/`CircuitBreaker`/`PipelineDeps.breakers`/`Decision="failed_breaker"`/`RunTrace.memory_degraded`。

- [ ] **Step 2.1: 一次性写完全部核心测试**

`tests/unit/crosscutting/test_breaker.py`（完整文件）：

```python
"""熔断器状态机核心行为：三态转换、成功清零、half_open 探测语义（假时钟，不睡真时间）。"""

from quanta_bot.crosscutting.breaker import (
    BreakerRegistry,
    BreakerState,
    CircuitBreaker,
)


class FakeClock:
    """可推进的单调时钟（测试用）。"""

    def __init__(self) -> None:
        self.now = 1000.0

    def __call__(self) -> float:
        return self.now

    def advance(self, seconds: float) -> None:
        self.now += seconds


def test_consecutive_failures_trip_open_then_half_open_recovery() -> None:
    """连续失败 5 次进 open；冷却到点转 half_open 放行探测；探测成功恢复 closed。"""
    clock = FakeClock()
    breaker = CircuitBreaker("llm", failure_threshold=5, open_seconds=60.0, clock=clock)
    for _ in range(5):
        assert breaker.allow_request() is True
        breaker.record_failure()
    assert breaker.state == BreakerState.OPEN
    assert breaker.allow_request() is False  # 冷却内拒绝
    clock.advance(61.0)  # 过冷却
    assert breaker.allow_request() is True  # 到点放行探测
    assert breaker.state == BreakerState.HALF_OPEN
    breaker.record_success()  # 探测成功
    assert breaker.state == BreakerState.CLOSED
    assert breaker.failure_count == 0


def test_success_resets_consecutive_failure_count() -> None:
    """成功一次清零计数——4败1胜4败从未连续 5 次，保持 closed。"""
    breaker = CircuitBreaker("memory", failure_threshold=5, open_seconds=30.0, clock=FakeClock())
    for _ in range(4):
        breaker.record_failure()
    breaker.record_success()
    for _ in range(4):
        breaker.record_failure()
    assert breaker.state == BreakerState.CLOSED


def test_half_open_probe_failure_reopens_with_fresh_cooldown() -> None:
    """half_open 探测失败立即回 open 且冷却重新计时。"""
    clock = FakeClock()
    breaker = CircuitBreaker("main_service", failure_threshold=5, open_seconds=30.0, clock=clock)
    for _ in range(5):
        breaker.record_failure()
    clock.advance(31.0)
    assert breaker.allow_request() is True  # 转half_open放行
    breaker.record_failure()  # 探测失败
    assert breaker.state == BreakerState.OPEN
    assert breaker.allow_request() is False  # 新冷却内拒绝
    clock.advance(31.0)
    assert breaker.allow_request() is True  # 再到点再探测


def test_registry_default_domains_follow_consensus() -> None:
    """默认注册表三域齐备（llm/memory/main_service——grill 共识分域）。"""
    registry = BreakerRegistry()
    assert registry.llm.name == "llm"
    assert registry.memory.name == "memory"
    assert registry.main_service.name == "main_service"
    assert registry.llm is not registry.memory is not registry.main_service
```

`tests/unit/pipeline/test_pipeline.py` 追加（放在文件末尾；复用本文件既有 `_m3_deps`/`SpyTracer`/`_DECISION_JSON`，新增两个间谍类与一个事件工厂）：

```python
# ---- M5-A 熔断三态：分通道行为（LLM 静默 / 记忆降级 / 主服务快速失败）----

from quanta_bot.crosscutting.breaker import BreakerState  # noqa: E402（追加区就近 import）


def _mention_event(comment_id: int) -> TriggerEvent:
    """熔断/频率测试通用 @ 触发事件（post_id=1 / commenter=42）。"""
    return TriggerEvent(
        event_id=f"m5-{comment_id}",
        comment_id=comment_id,
        post_id=1,
        commenter_user_id=42,
        content="@框框 这门课怎么样，求建议",
        mentioned_bot=True,
    )


class CountingMemoryStore:
    """记忆库调用计数间谍（熔断 open 时断言零调用——快速失败不敲已熔断依赖）。"""

    def __init__(self) -> None:
        self.recall_count = 0

    async def recall(self, user_id, persona_version, query_text, limit):
        self.recall_count += 1
        return ()

    async def negative_feedback(self, user_id, persona_version):
        return ()

    async def apply_ops(self, user_id, persona_version, ops):
        return None


class ExplodingFetcher:
    """拉取必抛的 fake 评论树（连续失败进 open 的数据源）。"""

    async def fetch_context(self, event: TriggerEvent) -> PostThread:
        raise CommentFetchError("主服务挂了（测试注入）")

    async def fetch_floors(self, post_id: int) -> tuple[CommentNode, ...]:
        raise CommentFetchError("主服务挂了（测试注入）")


def _open_breaker(breaker) -> None:
    """预置 open 态（5 连败=生产阈值）。"""
    for _ in range(5):
        breaker.record_failure()


async def test_llm_breaker_open_silent_skip(tmp_path) -> None:
    """LLM 熔断 open → failed_breaker 静默不回，LLM 零调用。"""
    llm = FakeLLM()
    deps = _m3_deps(llm=llm, tmp_path=tmp_path)
    _open_breaker(deps.breakers.llm)
    decision = await run(_mention_event(101), deps)
    assert decision == "failed_breaker"
    assert llm.calls == []  # 快速失败：不敲已熔断依赖


async def test_memory_breaker_open_degrades_but_replies(tmp_path) -> None:
    """记忆熔断 open → 降级照常回复（memory_degraded 留痕），记忆库零调用。"""
    tracer = SpyTracer()
    store = CountingMemoryStore()
    deps = _m3_deps(
        llm=FakeLLM(responses=[_DECISION_JSON, "降级也要接住你"]), tracer=tracer, tmp_path=tmp_path
    )
    deps.memory_store = store  # 替换为计数间谍
    _open_breaker(deps.breakers.memory)
    decision = await run(_mention_event(102), deps)
    assert decision == "replied"  # 记忆是可降级通道：无记忆照常回复
    assert store.recall_count == 0
    assert tracer.traces[-1].memory_degraded is True


async def test_main_service_breaker_open_fails_silently(tmp_path) -> None:
    """主服务熔断 open → failed 静默不回（写不进去回了也白回），评论树零调用。"""
    fetcher = CountingFetcher()
    deps = _m3_deps(tree=fetcher, tmp_path=tmp_path)
    _open_breaker(deps.breakers.main_service)
    decision = await run(_mention_event(103), deps)
    assert decision == "failed"
    assert fetcher.count == 0


async def test_consecutive_fetch_failures_trip_main_service_breaker(tmp_path) -> None:
    """拉取连续失败 5 次 → 熔断进 open；第 6 条触发即使依赖恢复也快速失败。"""
    deps = _m3_deps(tree=ExplodingFetcher(), tmp_path=tmp_path)
    for comment_id in range(201, 206):  # 5 条失败（各自记账一次）
        decision = await run(_mention_event(comment_id), deps)
        assert decision == "failed"
    assert deps.breakers.main_service.state == BreakerState.OPEN
    recovered_fetcher = CountingFetcher()  # 依赖已恢复
    deps.comment_tree = recovered_fetcher
    decision = await run(_mention_event(206), deps)
    assert decision == "failed"  # 熔断未到冷却——仍快速失败
    assert recovered_fetcher.count == 0
```

- [ ] **Step 2.2: eval case + runner 支持 + manifest**

`tests/eval/_runner.py`：`EvalCase` 增字段（`judge: dict | None = None` 之后）：

```python
    breakers_open: list[str] = []  # M5-A：预置熔断 open 域（llm/memory/main_service）
```

`run_case` 中 `deps, writer, tracer, spy_store = build_case_deps(...)` 之后、`pipeline.run` 之前加：

```python
    for breaker_name in case.breakers_open:  # M5-A：预置熔断 open（5 连败=生产阈值）
        breaker = getattr(deps.breakers, breaker_name)
        for _ in range(5):
            breaker.record_failure()
```

Create `eval/cases/pipeline-breaker-llm.yaml`：

```yaml
# LLM 熔断 open → 快速静默不回（failed_breaker）：预置 llm 域 5 连败进 open，
# 决策调用不应发生（llm_script 仅在熔断失效时被消费——decision_is 断言兜底）。
id: pipeline-breaker-llm
scenario: 0
scenario_name: LLM 熔断 open 静默不回
mode: 专业答疑
tier: pipeline
memories: []
breakers_open: [llm]
trigger:
  post: { postId: 2101, userId: 1, title: 熔断演练帖, content: "AI 学长今天状态如何" }
  comment: { commentId: 9101, userId: 42, content: "@框框 帮我总结下这帖" }
llm_script:
  - '{"should_reply": true, "mode": "专业答疑", "confidence": 0.9, "reason": "总结请求"}'
  - "若熔断失效被调用，此回复不应出现"
deterministic:
  - { assert: decision_is, expected: failed_breaker }
judge: null
```

`eval/gate-manifest.yaml` 的 `fast_gate_ids` 追加 `pipeline-breaker-llm`。

- [ ] **Step 2.3: 批量验证（一次性跑齐）**

```
uv run ruff format src tests
uv run ruff check src tests
uv run pytest tests/unit -q
uv run pytest tests/eval -q
```
Expected: 全绿（既有用例零回归 + 新增 8 个用例通过；若既有用例因 PipelineDeps 新字段破坏，属装配方式假设问题——修测试调用方式而非删断言）。

- [ ] **Step 2.4: Commit**

```bash
git add QuantaBot/src QuantaBot/tests QuantaBot/eval QuantaBot/.env.example
git commit -m "feat(breaker): M5-A 熔断三态——三域状态机+管线接线+核心测试+eval case

影响面：LLM 连续失败 5 次熔断后静默不回(failed_breaker)；记忆/RAG 域熔断降级照常回复(memory_degraded 留痕)；主服务域熔断拉取前快速失败。half_open=放行下一条真实触发探测。"
```

---

### Task 3: 文档回写

- [ ] **Step 3.1: 技术选型.md 回填熔断参数**（决策变化先改唯一权威）：§12 风险表「熔断」行回填参数口径（阈值 5 / 冷却 60-30-30s / half_open 真实探测 / 进程内存态），并在 §10.1 目录结构 `breaker.py` 行补「M5-A 已落地」注记。
- [ ] **Step 3.2: 总计划.md**：勾选 M5-熔断三态 checkbox；§7 变更记录追加 M5-A 完成条目。
- [ ] **Step 3.3: AGENTS.md**：变更记录追加 v0.4.7（M5-A 熔断落地；§2 目录树 breaker 行状态更新）。
- [ ] **Step 3.4: Commit**

```bash
git add QuantaBot/docs QuantaBot/总计划.md QuantaBot/AGENTS.md
git commit -m "docs: M5-A 熔断三态回写（技术选型参数口径/总计划勾选/AGENTS 变更记录）"
```

---

## 验收标准

1. `uv run pytest tests/unit tests/eval -q` 全绿（新增：状态机 4 测 + 管线 4 测 + eval 1 case）；
2. Fast gate 通过（`uv run ruff format --check src tests && uv run ruff check src tests && uv run pytest -m "not persona" -q`）；
3. 三域分叉行为可查：`failed_breaker`/`memory_degraded`/快速失败 `failed` 均有测试断言 + eval case 进 fast_gate 常跑；
4. 技术选型/总计划/AGENTS 回写完成。
