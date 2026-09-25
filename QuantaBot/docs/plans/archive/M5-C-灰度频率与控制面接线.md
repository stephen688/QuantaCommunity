# M5-C 灰度频率与控制面接线 实施计划

> **For agentic workers:** 按 Task 顺序执行；Task 内「先完整实现 → 末尾统一批量测试」为定制节拍。步骤用 checkbox 跟踪。**前置：M5-A/B 已合入**（管线 diff 基于 A+B 合入后状态）。

**Goal:** 把两个「轮询了但没人消费」的控制面键接进业务（灰度白名单放量模式、persona_version 远程键优先），新增频率约束（同帖 3 条/48h + 同用户 5 条/天），并把灰度键脏数据兜底从「清空=全量放开」改为「沿用上次有效值」。

**Architecture:** 新增纯逻辑 `crosscutting/rate_limit.py`（INCR+TTL 计数闸，fail-open）；管线在幂等后插入灰度→频率两道零成本闸门；persona_version 远程键优先、缺省回落本地 hash（记忆隔离机制复用 M3 已有能力，只换版本号来源）。

**Tech Stack:** 无新依赖。

**Spec:** `QuantaBot/总计划.md` §2 M5 节 + 本文件「需求结论」。执行前先读 `QuantaBot/AGENTS.md` §0/§4/§5。

---

## 需求结论（grill-me 两轮对齐，2026-09-25）

1. **灰度=放量模式**：白名单非空→只对白名单用户回复，其余 `skipped_graylist` 静默；白名单空/缺→全量。上线新人格先填灰度名单，观察后清空=全量。不做 A/B 双人格（复杂度不值），帖子维度不做（记 Phase 2 备选）。
2. **灰度键脏数据兜底**：非空但解析失败→**沿用上一次有效值**（灰度期间键损坏≠事故性全量放开）；键缺失/被清空→()=全量（运营清空=放量语义，不受影响）。
3. **persona_version 远程键优先**：键有值→覆盖本地 prompts hash 作为记忆写入/检索/摘要缓存的版本号；键空→本地 hash。回滚=改键回旧版本号，旧版本记忆检索自动失效（M3 三重过滤已实现，验收②口径）。
4. **频率保守值**：同帖 AI 最多回 **3 条**（窗口 48h，对齐对话链 TTL）；同用户每天最多触发 **5 条回复**（UTC 日界）。超限静默 `skipped_rate_limit`。计数含本次；先幂等后频率（重复投递不重复计数）；被审核/决策拦掉的触发也计数（宁可少回不可多回）。
5. **频率 Redis 故障**：fail-open 放行 + WARNING（刷屏风险由保守值+MQ 背压兜底；Redis 抖动不该让 Bot 全静默）。
6. **关卡顺序（最终态）**：kill→幂等→**灰度→频率**→预检→硬规则→拉取→记忆→**成本读档**→决策→…→生成→泄漏扫描→写库（本计划插入灰度/频率两闸；成本闸 B 已插）。

## Global Constraints

（同 M5-A/B，全文适用）
- **任务粒度（用户 2026-09-25 定制）**：大块 Task，先完整实现再统一批量测试。
- **测试纪律**：只写核心链路测试（闸门分叉、上限触发、脏数据兜底、版本覆盖），不写琐碎小测。
- 分层硬规则；配置收口 settings.py；中文注释+行内业务注释；禁单字母命名。
- git 在 `QuantaCommunity` 根执行；Conventional Commits；灰度/频率属横切，提交注明影响面。
- 执行顺序：A → B → **C** → D。
- 每 Task 完成必跑 ruff 双命令 + `uv run pytest tests/unit -q`。

## 文件结构

- Create: `src/quanta_bot/crosscutting/rate_limit.py`
- Create: `tests/unit/crosscutting/test_rate_limit.py`
- Create: `eval/cases/pipeline-killswitch-gate.yaml`、`eval/cases/pipeline-graylist-gate.yaml`、`eval/cases/pipeline-rate-limit-post.yaml`
- Modify: `src/quanta_bot/crosscutting/killswitch.py`（parse_snapshot 脏数据沿用旧值）
- Modify: `src/quanta_bot/crosscutting/ports.py`（Decision 增 `skipped_graylist`/`skipped_rate_limit`）
- Modify: `src/quanta_bot/infra/settings.py`（频率四参数）
- Modify: `src/quanta_bot/pipeline/pipeline.py`（灰度/频率闸 + persona_version 远程优先）
- Modify: `src/quanta_bot/composition.py`（deps 接线）
- Modify: `tests/unit/crosscutting/test_killswitch.py`、`tests/unit/pipeline/test_pipeline.py`（追加）
- Modify: `tests/eval/_runner.py`（EvalCase 增 switches/rate_post_count_preset）、`eval/gate-manifest.yaml`、`.env.example`

---

### Task 1: 频率闸 + 灰度闸 + 版本键接线 + 脏数据兜底（一次性完整实现）

**Interfaces（Produces，D 依赖）:**
- `rate_limit.check_and_count(kv, post_id, user_id, post_max, post_window_hours, user_max, user_window_hours) -> RateLimitVerdict`（NamedTuple：`allowed: bool`、`reason: str`）
- `rate_limit.post_key(post_id) -> str` / `rate_limit.user_key(user_id) -> str`（`quantabot:ratelimit:post:{id}` / `quantabot:ratelimit:user:{id}`）
- `killswitch.parse_snapshot(raw, previous=None)`（签名扩展——脏 graylist 沿用 previous）
- `PipelineDeps` 新字段：`rate_limit_post_max: int = 3`、`rate_limit_post_window_hours: int = 48`、`rate_limit_user_max: int = 5`、`rate_limit_user_window_hours: int = 24`
- `Decision` 增 `"skipped_graylist"`、`"skipped_rate_limit"`

- [ ] **Step 1.1: settings.py 增频率参数**

熔断/成本块之后追加：

```python
    # ---- M5 频率约束（保守值；超限静默 skipped_rate_limit——PRD F6「MVP 先设保守值」）----
    rate_limit_post_max: int = 3  # 同帖回复上限（窗口对齐对话链 TTL 48h）
    rate_limit_post_window_hours: int = 48
    rate_limit_user_max: int = 5  # 同用户每日回复上限（UTC 日界）
    rate_limit_user_window_hours: int = 24
```

`.env.example` 追加对应条目。

- [ ] **Step 1.2: 新建 crosscutting/rate_limit.py（完整文件）**

```python
"""crosscutting/rate_limit —— 频率约束（M5：同帖上限 + 同用户频率，保守值，超限静默）。

职责：两级计数闸门——同帖（post_id 维度，窗口=对话链 TTL 口径 48h）与同用户（commenter 维度，
      UTC 日界）；check_and_count 过闸即计数（INCR 含本次，天然"第 N+1 次拦截"语义）。
边界：fail-open——kv 读写失败放行 + WARNING（Redis 抖动不该让 Bot 全静默；刷屏风险由保守值与
      MQ 背压兜底）；不做滑动窗口精确算法（INCR+TTL 首次定窗即可，保守值场景够用）；
      不做拦截回滚（一维超限时另一维已计数——宁可少回不可多回）。
键口径：quantabot:ratelimit:post:{post_id} / quantabot:ratelimit:user:{user_id}（P0-7 命名空间）。
已知坑：管线关卡序=幂等在前频率在后——重复投递不重复计数；被审核/决策拦掉的触发同样计数
      （计数发生在闸门处，早于后续拦截——防刷屏语义优先）。
"""

import logging
from typing import NamedTuple

from quanta_bot.crosscutting.ports import KeyValueStore

logger = logging.getLogger(__name__)

RATE_LIMIT_KEY_PREFIX = "quantabot:ratelimit:"


def post_key(post_id: int) -> str:
    """同帖回复计数键。"""
    return f"{RATE_LIMIT_KEY_PREFIX}post:{post_id}"


def user_key(user_id: int) -> str:
    """同用户回复计数键。"""
    return f"{RATE_LIMIT_KEY_PREFIX}user:{user_id}"


class RateLimitVerdict(NamedTuple):
    """频率闸门判定结果（reason 进决策日志——用户问"为什么不回我"可对质）。"""

    allowed: bool
    reason: str


async def check_and_count(
    kv: KeyValueStore,
    post_id: int,
    user_id: int,
    post_max: int,
    post_window_hours: int,
    user_max: int,
    user_window_hours: int,
) -> RateLimitVerdict:
    """过闸即计数（含本次）；任一维超限→拦截。"""
    try:
        post_count = await kv.increment(post_key(post_id), 1, post_window_hours * 3600)
        user_count = await kv.increment(user_key(user_id), 1, user_window_hours * 3600)
    except Exception as exc:  # fail-open：频率是防刷屏约束非安全红线
        logger.warning("频率键读写失败，放行（fail-open）：%s", exc)
        return RateLimitVerdict(True, "频率键不可用放行")
    if post_count > post_max:
        return RateLimitVerdict(
            False, f"同帖回复达上限（{post_count}/{post_max}，窗口 {post_window_hours}h）"
        )
    if user_count > user_max:
        return RateLimitVerdict(
            False, f"同用户频率达上限（{user_count}/{user_max}，窗口 {user_window_hours}h）"
        )
    return RateLimitVerdict(True, "频率闸门通过")
```

- [ ] **Step 1.3: killswitch.py 脏数据沿用旧值**

`parse_snapshot` 签名与 graylist 降级分支改为（kill/persona 解析不变；docstring 同步）：

```python
def parse_snapshot(
    raw: dict[str, str | None], previous: ControlPlaneSnapshot | None = None
) -> ControlPlaneSnapshot:
    """把三键原始值解析为快照（脏数据宽容降级：graylist 非 JSON 时沿用上次有效值——
    灰度期间键损坏≠事故性全量放开；键缺失/被清空=()=全量，运营清空即放量，不受影响）。"""
    kill = (raw.get(SWITCH_KILL_KEY) or "").strip().lower() == "true"
    graylist: tuple[int, ...] = ()
    gray_raw = (raw.get(SWITCH_GRAYLIST_KEY) or "").strip()
    if gray_raw:
        try:
            graylist = tuple(int(x) for x in json.loads(gray_raw))
        except (ValueError, TypeError):
            graylist = previous.graylist if previous is not None else ()
            logger.warning("灰名单键值非 JSON 整数数组，沿用上次有效值 %r", graylist)
    persona_version = (raw.get(SWITCH_PERSONA_VERSION_KEY) or "").strip()
    return ControlPlaneSnapshot(kill=kill, graylist=graylist, persona_version=persona_version)
```

`ControlPlane.refresh` 传入旧快照：

```python
    async def refresh(self) -> None:
        """读三键并更新快照（也可手动调用，测试/运维即时生效用）。"""
        raw: dict[str, str | None] = {key: await self._kv.get(key) for key in _ALL_SWITCH_KEYS}
        self._snapshot = parse_snapshot(raw, previous=self._snapshot)
```

（模块头 docstring 键口径行同步：graylist 脏数据沿用上次有效值。）

- [ ] **Step 1.4: ports + pipeline 接线**

`crosscutting/ports.py` Decision Literal 增（`"skipped_cost_exhausted"` 之后）：

```python
    "skipped_graylist",  # 灰度期间非白名单用户，静默（M5-C 放量模式）
    "skipped_rate_limit",  # 同帖/同用户频率达上限，静默（M5-C 保守值）
```

`pipeline/pipeline.py`（基于 A+B 合入后状态）：

① import 区加：

```python
from quanta_bot.crosscutting import rate_limit
```

② `PipelineDeps` 末尾追加：

```python
    # M5-C 频率约束（保守值：同帖 3 条/48h、同用户 5 条/天）
    rate_limit_post_max: int = 3
    rate_limit_post_window_hours: int = 48
    rate_limit_user_max: int = 5
    rate_limit_user_window_hours: int = 24
```

③ `_execute` ② 幂等块之后、③ 预检之前插入两道闸门：

```python
    # ②a 灰度闸门（放量模式：白名单非空→只回白名单用户；空/缺→全量——运营清空即放量）
    graylist = deps.control_plane.snapshot.graylist
    if graylist and event.commenter_user_id not in graylist:
        return _Outcome("skipped_graylist", f"灰度期间非白名单用户（{event.commenter_user_id}）静默")
    # ②b 频率闸门（同帖上限 + 同用户频率；fail-open；计数含本次——防刷屏语义优先）
    rate_verdict = await rate_limit.check_and_count(
        deps.kv,
        event.post_id,
        event.commenter_user_id,
        deps.rate_limit_post_max,
        deps.rate_limit_post_window_hours,
        deps.rate_limit_user_max,
        deps.rate_limit_user_window_hours,
    )
    if not rate_verdict.allowed:
        return _Outcome("skipped_rate_limit", rate_verdict.reason)
```

④ ⑥ 记忆块的版本号行改为远程优先：

```python
        # persona_version：控制面远程键优先（回滚=改键，旧版本记忆检索自动失效）；空=本地 hash
        remote_persona_version = deps.control_plane.snapshot.persona_version
        persona_version = remote_persona_version or deps.persona.persona_version
```

（原 `persona_version = deps.persona.persona_version` 行删除；后续 recall/apply_ops/摘要缓存引用不变。）

⑤ 模块头 docstring 边界行改为：「频率/灰度闸门与 persona_version 远程键已落地（M5-C）；消费暂停在 consumer（kill）」。

- [ ] **Step 1.5: composition.py 接线**

`PipelineDeps(...)` 参数追加：

```python
        rate_limit_post_max=settings.rate_limit_post_max,
        rate_limit_post_window_hours=settings.rate_limit_post_window_hours,
        rate_limit_user_max=settings.rate_limit_user_max,
        rate_limit_user_window_hours=settings.rate_limit_user_window_hours,
```

- [ ] **Step 1.6: 导入自检**

Run: `uv run python -c "from quanta_bot.composition import build_runtime; from quanta_bot.infra.settings import Settings; build_runtime(Settings())"`
Expected: 无异常。

---

### Task 2: 批量核心测试 + 3 个 eval case + 门禁接线

- [ ] **Step 2.1: 一次性写完全部核心测试**

Create `tests/unit/crosscutting/test_rate_limit.py`（完整文件）：

```python
"""频率闸门核心行为：同帖上限、同用户上限、kv 故障 fail-open。"""

from quanta_bot.crosscutting import rate_limit
from quanta_bot.infra.kv import InMemoryKV


class ExplodingIncrementKV(InMemoryKV):
    """increment 必抛的 kv（fail-open 数据源）。"""

    async def increment(self, key: str, amount: int, ttl_seconds: int) -> int:
        raise RuntimeError("redis 挂了（测试注入）")


async def test_post_limit_blocks_at_max_plus_one() -> None:
    """同帖第 4 条（=max+1）拦截：3 条全过 → 第 4 条 skipped（计数含本次）。"""
    kv = InMemoryKV()
    for attempt in range(3):
        verdict = await rate_limit.check_and_count(kv, 1, 42, 3, 48, 5, 24)
        assert verdict.allowed is True, f"第 {attempt + 1} 条应放行"
    verdict = await rate_limit.check_and_count(kv, 1, 42, 3, 48, 5, 24)
    assert verdict.allowed is False
    assert "同帖" in verdict.reason


async def test_user_limit_blocks_across_posts() -> None:
    """同用户跨帖第 6 条（=max+1）拦截：5 帖全过 → 第 6 帖拦截。"""
    kv = InMemoryKV()
    for post_id in range(1, 6):  # 5 帖各 1 条（每帖 post 维度未超）
        verdict = await rate_limit.check_and_count(kv, post_id, 42, 3, 48, 5, 24)
        assert verdict.allowed is True
    verdict = await rate_limit.check_and_count(kv, 99, 42, 3, 48, 5, 24)
    assert verdict.allowed is False
    assert "同用户" in verdict.reason


async def test_kv_failure_fails_open() -> None:
    """频率键读写失败 → 放行（fail-open，WARNING 留痕）。"""
    verdict = await rate_limit.check_and_count(ExplodingIncrementKV(), 1, 42, 3, 48, 5, 24)
    assert verdict.allowed is True
```

`tests/unit/crosscutting/test_killswitch.py` 追加：

```python
def test_dirty_graylist_keeps_previous_valid_value() -> None:
    """灰度键脏数据（非 JSON）→ 沿用上次有效值（脏数据≠事故性全量放开）。"""
    previous = parse_snapshot({SWITCH_GRAYLIST_KEY: "[1, 2]"})
    snap = parse_snapshot({SWITCH_GRAYLIST_KEY: "not-json"}, previous=previous)
    assert snap.graylist == (1, 2)
```

（既有 `test_parse_snapshot_graylist_and_persona` 的 `bad = parse_snapshot(...)` 无 previous → 仍为 ()，保持不动即绿——行为变更只影响带 previous 的路径。）

`tests/unit/pipeline/test_pipeline.py` 追加（import 区补 `SWITCH_GRAYLIST_KEY, SWITCH_PERSONA_VERSION_KEY`）：

```python
# ---- M5-C 灰度/频率/版本键：闸门分叉 + 远程版本覆盖 ----


async def test_graylist_gate_blocks_non_whitelisted(tmp_path) -> None:
    """灰度白名单非空且触发者不在名单 → skipped_graylist 静默。"""
    deps = _m3_deps(llm=FakeLLM(responses=[_DECISION_JSON, "回复"]), tmp_path=tmp_path)
    await deps.kv.set(SWITCH_GRAYLIST_KEY, "[999]", ttl_seconds=3600)
    await deps.control_plane.refresh()
    decision = await run(_mention_event(401), deps)
    assert decision == "skipped_graylist"


async def test_graylist_empty_allows_all(tmp_path) -> None:
    """白名单空/缺 → 全量放行（运营清空即放量）。"""
    deps = _m3_deps(llm=FakeLLM(responses=[_DECISION_JSON, "白名单空全量回复"]), tmp_path=tmp_path)
    decision = await run(_mention_event(402), deps)
    assert decision == "replied"


async def test_rate_limit_gates_after_post_max(tmp_path) -> None:
    """同帖第 4 条触发 → skipped_rate_limit（前 3 条 replied，同 user 未触用户上限）。"""
    deps = _m3_deps(llm=FakeLLM(responses=[_DECISION_JSON, "接住"]), tmp_path=tmp_path)
    # FakeLLM 剧本只够一次决策+生成——三条 replied 各用独立 deps 但共享 kv 计数
    for comment_id in (501, 502, 503):
        single_deps = _m3_deps(
            llm=FakeLLM(responses=[_DECISION_JSON, "接住"]), kv=deps.kv, tmp_path=tmp_path
        )
        assert await run(_mention_event(comment_id), single_deps) == "replied"
    decision = await run(_mention_event(504), deps)
    assert decision == "skipped_rate_limit"


async def test_persona_version_remote_key_overrides_local(tmp_path) -> None:
    """persona_version 远程键有值 → 覆盖本地 hash（记忆写入/检索/摘要缓存随之切版本）。"""
    tracer = SpyTracer()
    deps = _m3_deps(
        llm=FakeLLM(responses=[_DECISION_JSON, "版本切换回复"]), tracer=tracer, tmp_path=tmp_path
    )
    await deps.kv.set(SWITCH_PERSONA_VERSION_KEY, "v-m5-rollback-test", ttl_seconds=3600)
    await deps.control_plane.refresh()
    decision = await run(_mention_event(505), deps)
    assert decision == "replied"
    assert tracer.traces[-1].persona_version == "v-m5-rollback-test"
```

- [ ] **Step 2.2: 3 个 eval case + runner + manifest**

`tests/eval/_runner.py`：`EvalCase` 增字段（`cost_preset_li` 之后）：

```python
    switches: dict[str, str] = {}  # M5-C：控制面键预置（完整键名→值；写后 refresh 快照）
    rate_post_count_preset: int = 0  # M5-C：同帖计数预置（含本次即超上限）
```

`run_case` 预置区追加（`cost_preset_li` 块之后）：

```python
    if case.switches:  # M5-C：控制面键预置后即时刷新（kill/graylist/persona_version 演练）
        for switch_key, switch_value in case.switches.items():
            await deps.kv.set(switch_key, switch_value, 3600)
        await deps.control_plane.refresh()
    if case.rate_post_count_preset:  # M5-C：同帖计数预置到上限（下一条触发即被拦）
        await deps.kv.set(
            rate_limit.post_key(trigger_event_from(case).post_id),
            str(case.rate_post_count_preset),
            48 * 3600,
        )
```

（import 区补 `from quanta_bot.crosscutting import rate_limit`。）

Create `eval/cases/pipeline-killswitch-gate.yaml`：

```yaml
# kill switch 置位 → 链路最前端短路（skipped_killswitch）：控制面键预置演练。
id: pipeline-killswitch-gate
scenario: 0
scenario_name: kill switch 置位短路
mode: 专业答疑
tier: pipeline
memories: []
switches:
  quantabot:switch:kill: "true"
trigger:
  post: { postId: 2103, userId: 1, title: 止血演练帖, content: "紧急止血" }
  comment: { commentId: 9103, userId: 42, content: "@框框 还在吗" }
llm_script:
  - '{"should_reply": true, "mode": "生活玩梗", "confidence": 0.9, "reason": "互动"}'
  - "kill 生效时不应被调用"
deterministic:
  - { assert: decision_is, expected: skipped_killswitch }
judge: null
```

Create `eval/cases/pipeline-graylist-gate.yaml`：

```yaml
# 灰度白名单非空且触发者(42)不在名单 → skipped_graylist 静默（放量模式）。
id: pipeline-graylist-gate
scenario: 0
scenario_name: 灰度非白名单静默
mode: 专业答疑
tier: pipeline
memories: []
switches:
  quantabot:switch:graylist: "[999]"
trigger:
  post: { postId: 2104, userId: 1, title: 灰度演练帖, content: "新人格灰度中" }
  comment: { commentId: 9104, userId: 42, content: "@框框 试试新人格" }
llm_script:
  - '{"should_reply": true, "mode": "生活玩梗", "confidence": 0.9, "reason": "互动"}'
  - "灰度拦截时不应被调用"
deterministic:
  - { assert: decision_is, expected: skipped_graylist }
judge: null
```

Create `eval/cases/pipeline-rate-limit-post.yaml`：

```yaml
# 同帖计数预置 3（=上限）→ 下一条触发计数到 4 超限 → skipped_rate_limit。
id: pipeline-rate-limit-post
scenario: 0
scenario_name: 同帖频率上限拦截
mode: 专业答疑
tier: pipeline
memories: []
rate_post_count_preset: 3
trigger:
  post: { postId: 2105, userId: 1, title: 频率演练帖, content: "学长别刷屏" }
  comment: { commentId: 9105, userId: 42, content: "@框框 再回一条试试" }
llm_script:
  - '{"should_reply": true, "mode": "生活玩梗", "confidence": 0.9, "reason": "互动"}'
  - "超限时不应被调用"
deterministic:
  - { assert: decision_is, expected: skipped_rate_limit }
judge: null
```

`eval/gate-manifest.yaml` 的 `fast_gate_ids` 追加三个 id（`pipeline-killswitch-gate`、`pipeline-graylist-gate`、`pipeline-rate-limit-post`）。

- [ ] **Step 2.3: 批量验证**

```
uv run ruff format src tests
uv run ruff check src tests
uv run pytest tests/unit -q
uv run pytest tests/eval -q
```
Expected: 全绿（新增 rate_limit 3 测 + killswitch 1 测 + pipeline 4 测 + eval 3 case）。

- [ ] **Step 2.4: Commit**

```bash
git add QuantaBot/src QuantaBot/tests QuantaBot/eval QuantaBot/.env.example
git commit -m "feat(control-plane): M5-C 灰度/频率/版本键接线——放量闸门+频率保守值+脏数据兜底

影响面：graylist 非空只回白名单用户(skipped_graylist)；同帖 3 条/48h、同用户 5 条/天超限静默(skipped_rate_limit)；灰度键脏数据沿用上次有效值；persona_version 远程键优先(回滚=改键)。"
```

---

### Task 3: 文档回写

- [ ] **Step 3.1: 技术选型.md §5.5③**：回填灰度放量语义（白名单非空=只回白名单；清空=全量）、脏数据兜底口径、persona_version 远程优先与回滚口径、频率保守值（3/48h、5/天）。
- [ ] **Step 3.2: 总计划.md**：勾选 M5-灰度与回滚 + M5-频率约束 两个 checkbox；§7 变更记录追加 M5-C 条目。
- [ ] **Step 3.3: Commit**

```bash
git add QuantaBot/docs QuantaBot/总计划.md
git commit -m "docs: M5-C 灰度/频率/版本键回写（放量语义/保守值/兜底口径）"
```

---

## 验收标准

1. `uv run pytest tests/unit tests/eval -q` 全绿（新增 8 测 + 3 eval case 全进 fast_gate）；
2. Fast gate 通过；
3. 闸门行为可查：skipped_graylist / skipped_rate_limit / 远程版本覆盖均有断言 + eval 常跑；
4. 灰度键脏数据不再导致「降级为空=全量放开」（沿用旧值测试钉死）；
5. 技术选型/总计划回写完成。
