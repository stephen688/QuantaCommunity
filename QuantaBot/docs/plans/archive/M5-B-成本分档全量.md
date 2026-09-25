# M5-B 成本分档全量 实施计划

> **For agentic workers:** 本计划按 Task 顺序执行；Task 内「先完整实现 → 末尾统一批量测试」为定制节拍。步骤用 checkbox 跟踪。**前置：M5-A 已合入**（本计划管线 diff 基于 A 合入后状态）。

**Goal:** 把只写不读的 Redis 日成本键升级为实时分档控制：充足→主模型 / 吃紧→轻模型（Qwen3.5-Plus）生成 / 枯竭→静默不回；轻模型按自身单价入账同一日键。

**Architecture:** `budget.read_tier()`（fail-open）判档；管线在决策前读档（枯竭短路 `skipped_cost_exhausted`），生成时按档选模型（吃紧只切生成，决策/摘要保主模型）；轻模型复用 OpenAI 兼容客户端（DeepSeekClient 同形态，指向 Qwen 端点）。

**Tech Stack:** 无新依赖（httpx OpenAI 兼容调用复用 `infra/deepseek.py` 的客户端）。

**Spec:** `QuantaBot/总计划.md` §2 M5 节 + 本文件「需求结论」。执行前先读 `QuantaBot/AGENTS.md` §0/§4/§5。

---

## 需求结论（grill-me 两轮对齐，2026-09-25）

1. **模型对**：主=deepseek-v4-flash **不动**（M4 冻结基线继续有效）；轻=Qwen3.5-Plus（技术选型 §4.6 钦定降级档，¥0.8/M 输入，约比主模型便宜 15 倍；key 复用 embedding 的 Qwen 凭据）。
2. **三档动作**：充足=一切照旧；吃紧=**只切生成**（决策/摘要单次 token 占比小，保主模型保判断质量）；枯竭=**静默不回**（新枚举 `skipped_cost_exhausted`；前置规则照跑但任何 LLM 调用不发；模板回复=承认限额且无信息量，违反「静默优于乱回」）。
3. **阈值**：吃紧=**20 元（20000 厘）** / 枯竭=**28 元（28000 厘）**（日目标 30 元留 2 元缓冲让在途流量收敛）；轻模型调用按自身单价折算、累加**同一个**日键；UTC 日界换键天然重置。
4. **读档位置**：所有 LLM 调用前——管线在 ⑥.5（决策前）读一次档，枯竭短路；档位传递到 ⑩ 生成处选模型。
5. **Redis 故障哲学（fail-open）**：成本键读失败→按充足继续+WARNING；成本写失败（生成后累加失败）→WARNING 继续（对账可见缺口）。成本是预算控制不是安全红线。
6. **轻模型人格质量**：**暂不跑** Persona eval（省钱；已知风险留档进 Maintain 观察项——轻模型路径仍有泄漏扫描三层审核兜底，质量崩坏可 kill switch 止血）。
7. **观测**：RunTrace 增 `cost_tier`/`model_used`，Langfuse generation metadata 同步——对账与档位归因依据。

## Global Constraints

（与 M5-A 相同，全文适用）
- **任务粒度（用户 2026-09-25 定制）**：大块 Task，先完整实现再统一批量测试；不写一点点就测。
- **测试纪律**：只写核心链路测试（分档判定、枯竭短路、吃紧切模型、fail-open），不写琐碎小测。
- 分层硬规则 `pipeline/memory → crosscutting → infra`；外部客户端只在 `composition.py` 创建；配置只从 settings.py 读；中文注释+行内业务注释。
- git 在 `QuantaCommunity` 根执行；Conventional Commits；成本分档属横切，提交注明影响面。
- 执行顺序：A → **B** → C → D。
- 每 Task 完成必跑：`uv run ruff format src tests` + `uv run ruff check src tests` + `uv run pytest tests/unit -q`。

## 文件结构

- Create: `eval/cases/pipeline-cost-exhausted.yaml`
- Create: `tests/unit/scripts/`（无——B 不加脚本）
- Modify: `src/quanta_bot/crosscutting/budget.py`（read_tier + CostTier）
- Modify: `src/quanta_bot/crosscutting/ports.py`（Decision 增 `skipped_cost_exhausted`）
- Modify: `src/quanta_bot/infra/settings.py`（阈值 + 轻模型五项 + 校验器）
- Modify: `src/quanta_bot/infra/deepseek.py`（FakeLLM/DeepSeekClient 增 `model_name`）
- Modify: `src/quanta_bot/pipeline/ports.py`（LLMClient 协议增 model_name；RunTrace 增 cost_tier/model_used）
- Modify: `src/quanta_bot/pipeline/pipeline.py`（⑥.5 读档闸 + ⑩ 模型路由 + add_cost fail-open）
- Modify: `src/quanta_bot/composition.py`（轻模型客户端 + deps 接线）
- Modify: `src/quanta_bot/infra/tracing.py`（generation metadata 增 model_used/cost_tier）
- Modify: `tests/unit/crosscutting/test_budget.py`、`tests/unit/pipeline/test_pipeline.py`（追加）
- Modify: `tests/eval/_runner.py`（EvalCase 增 cost_preset_li）、`eval/gate-manifest.yaml`、`.env.example`

---

### Task 1: 分档实现 + 轻模型路由 + 装配（一次性完整实现）

**Interfaces（Produces，C/D 依赖）:**
- `budget.CostTier = Literal["sufficient", "tight", "exhausted"]`
- `budget.read_tier(kv, day, tight_threshold_li, exhausted_threshold_li) -> CostTier`（fail-open）
- `PipelineDeps` 新字段：`light_llm: LLMClient | None = None`、`cost_tight_threshold_li: int = 20000`、`cost_exhausted_threshold_li: int = 28000`、`light_llm_input_price_per_mtok: float = 0.8`、`light_llm_output_price_per_mtok: float = 2.0`
- `LLMClient` 协议属性 `model_name: str`（FakeLLM/DeepSeekClient 均实现）
- `Decision` 增 `"skipped_cost_exhausted"`；`RunTrace` 增 `cost_tier: str | None = None`、`model_used: str | None = None`

- [ ] **Step 1.1: settings.py 增配置 + 阈值校验器**

超时/熔断块之后追加：

```python
    # ---- M5 成本分档（充足→主模型 / 吃紧→轻模型生成 / 枯竭→静默；单位=厘，1 元=1000 厘）----
    # 阈值口径（grill 2026-09-25）：日目标 30 元（PRD §4）；吃紧 20 / 枯竭 28（留 2 元在途收敛缓冲）
    cost_tight_threshold_li: int = 20000
    cost_exhausted_threshold_li: int = 28000
    # 轻模型（吃紧档生成专用；OpenAI 兼容端点——技术选型 §4.6 钦定 Qwen3.5-Plus 降级档）
    light_llm_base_url: str = ""  # 空=未配置（吃紧档回退主模型，WARNING 留痕）
    light_llm_api_key: str = ""
    light_llm_model: str = "qwen3.5-plus"
    light_llm_input_price_per_mtok: float = 0.8  # 技术选型 §4.3 口径
    light_llm_output_price_per_mtok: float = 2.0  # 输出单价部署前按云厂商定价页核对（.env.example 注记）
```

`Settings` 内追加校验器（与 `require_admin_token_in_prod` 平级）：

```python
    @model_validator(mode="after")
    def validate_cost_thresholds(self) -> "Settings":
        """吃紧阈值必须严格小于枯竭阈值（配错即启动失败，不静默带病跑）。"""
        if self.cost_tight_threshold_li >= self.cost_exhausted_threshold_li:
            raise ValueError("cost_tight_threshold_li 必须小于 cost_exhausted_threshold_li")
        return self
```

`.env.example` 追加对应条目（含 dashscope compatible-mode 端点示例与「输出单价部署前核对」注记）。

- [ ] **Step 1.2: budget.py 扩展分档判定**

模块头 docstring 边界行更新（「只做记账，不做分档切换」改为「M5-B 起含分档判定 read_tier；切换执行在管线」）。文件末尾追加：

```python
import logging

from typing import Literal

logger = logging.getLogger(__name__)

# 成本档位（充足→主模型 / 吃紧→轻模型生成 / 枯竭→静默不回——AGENTS Do）
CostTier = Literal["sufficient", "tight", "exhausted"]


async def read_tier(
    kv: KeyValueStore,
    day: date,
    tight_threshold_li: int,
    exhausted_threshold_li: int,
) -> CostTier:
    """读当日累计判定成本档位（fail-open：读失败按充足继续——成本是预算控制非安全红线，
    Redis 抖动不该让 Bot 全静默；缺口由 Langfuse 每日对账发现）。"""
    try:
        cost_li = await read_cost(kv, day)
    except Exception as exc:
        logger.warning("成本键读取失败，按充足档继续（fail-open）：%s", exc)
        return "sufficient"
    if cost_li >= exhausted_threshold_li:
        return "exhausted"
    if cost_li >= tight_threshold_li:
        return "tight"
    return "sufficient"
```

（import 归位到文件头部：`logging` 与 `typing.Literal` 按现有 import 排版合并。）

- [ ] **Step 1.3: ports/客户端 model_name**

`crosscutting/ports.py` Decision Literal 增（`"failed_breaker"` 之后）：

```python
    "skipped_cost_exhausted",  # 日成本达枯竭阈值，静默不回（M5-B 分档）
```

`pipeline/ports.py`：`LLMClient` 协议加属性声明（docstring 下方）：

```python
    model_name: str  # 实际使用的模型名（trace/对账归因——M5-B）
```

`RunTrace` 末尾追加：

```python
    cost_tier: str | None = None  # 本次生成前的成本档位（充足/吃紧/枯竭——M5-B 归因）
    model_used: str | None = None  # 生成实际使用的模型名（轻模型切换留痕——对账依据）
```

`infra/deepseek.py`：`FakeLLM.__init__` 增参数 `model_name: str = "fake-llm"` 并存 `self.model_name`；`DeepSeekClient` 加：

```python
    @property
    def model_name(self) -> str:
        """当前模型名（OpenAI 兼容端点通用——轻模型实例指向 Qwen 端点）。"""
        return self._model
```

- [ ] **Step 1.4: pipeline.py 成本闸 + 模型路由 + add_cost fail-open**

基于 A 合入后状态：

① import 区已有 `budget`；`PipelineDeps` 末尾（`breakers` 之后）追加：

```python
    # M5-B 成本分档（吃紧只切生成；枯竭静默；轻模型未配置回退主模型）
    light_llm: LLMClient | None = None  # 轻模型客户端（composition 注入；None=未配置）
    cost_tight_threshold_li: int = 20000  # 吃紧阈值（厘）
    cost_exhausted_threshold_li: int = 28000  # 枯竭阈值（厘）
    light_llm_input_price_per_mtok: float = 0.8  # 轻模型输入单价（元/百万 token）
    light_llm_output_price_per_mtok: float = 2.0  # 轻模型输出单价（元/百万 token）
```

② `_Outcome` 末尾追加：

```python
    cost_tier: str | None = None  # 成本档位留痕（M5-B）
    model_used: str | None = None  # 生成实际模型留痕（M5-B）
```

③ `run()` 的 RunTrace 构造追加：

```python
            cost_tier=outcome.cost_tier,
            model_used=outcome.model_used,
```

④ `_execute` ⑥ 记忆块之后、⑦ LLM 熔断守卫之前插入成本闸：

```python
        # ⑥.5 成本读档（G6 分档：充足→主模型 / 吃紧→轻模型生成 / 枯竭→静默不回；读档 fail-open）
        cost_tier = await budget.read_tier(
            deps.kv,
            datetime.now(UTC).date(),
            deps.cost_tight_threshold_li,
            deps.cost_exhausted_threshold_li,
        )
        if cost_tier == "exhausted":
            return _Outcome(
                "skipped_cost_exhausted",
                f"日成本达枯竭阈值（{deps.cost_exhausted_threshold_li} 厘），静默不回",
                cost_tier=cost_tier,
            )
```

⑤ ⑩ 生成块替换为（模型路由 + 按实际模型单价折算 + add_cost fail-open）：

```python
        # ⑩ 生成（人格 system by mode）→ 成本 → ⑪ 写库；吃紧档只切生成（决策/摘要保主模型保判断质量）
        using_light = cost_tier == "tight" and deps.light_llm is not None
        generation_llm = deps.light_llm if using_light else deps.llm
        output = await generation.generate(
            event, decision_result, generation_llm, assembled.user_text, deps.persona
        )
        deps.breakers.llm.record_success()  # 决策+生成均成功（LLM 域恢复信号）
        input_price = (
            deps.light_llm_input_price_per_mtok if using_light else deps.llm_input_price_per_mtok
        )
        output_price = (
            deps.light_llm_output_price_per_mtok if using_light else deps.llm_output_price_per_mtok
        )
        cost_li = budget.estimate_cost_li(
            output.prompt_tokens, output.completion_tokens, input_price, output_price
        )
        try:  # 成本写失败 fail-open：对账可见缺口，不阻断已生成回复（M5-B 共识）
            cost_after = await budget.add_cost(
                deps.kv, cost_li, datetime.now(UTC).date(), deps.cost_key_ttl_hours
            )
        except Exception as exc:
            logger.warning("成本键累加失败（fail-open 继续）：%s", exc)
            cost_after = None
```

（注：A 计划中 `record_success` 紧跟 generate 之后一行，此处随块重排一并就位。）

⑥ replied 结尾 `_Outcome(...)` 追加：

```python
        cost_tier=cost_tier,
        model_used=generation_llm.model_name,
```

⑦ 模块头 docstring 边界行更新：去掉「频率/成本分档不在本层」中的成本分档半句 →「频率/灰度不在本层（M5-C）；熔断三态（M5-A）与成本分档（M5-B）已落地」。

- [ ] **Step 1.5: tracing.py generation metadata 增两字段**

`LangfuseTracer.record` 的 generation metadata 字典追加：

```python
                            "model_used": trace.model_used,
                            "cost_tier": trace.cost_tier,
```

- [ ] **Step 1.6: composition.py 轻模型装配**

LLM 装配块（主模型 `if settings.deepseek_api_key:` 之后、langfuse 之前）插入：

```python
        # 轻模型（M5-B 吃紧档生成专用）：OpenAI 兼容通用客户端指向 Qwen 端点；缺配置回退主模型
        light_llm: DeepSeekClient | None = None
        if settings.light_llm_base_url and settings.light_llm_api_key:
            light_llm = DeepSeekClient(
                settings.light_llm_base_url,
                settings.light_llm_api_key,
                settings.light_llm_model,
                settings.llm_timeout_seconds,
            )
            closers.append(light_llm.aclose)
        else:
            logger.warning("轻模型未配置——吃紧档生成回退主模型（成本分档降级）")
```

fake 分支无需处理（`light_llm` 缺省 None 即回退）。`PipelineDeps(...)` 参数追加：

```python
        light_llm=light_llm,
        cost_tight_threshold_li=settings.cost_tight_threshold_li,
        cost_exhausted_threshold_li=settings.cost_exhausted_threshold_li,
        light_llm_input_price_per_mtok=settings.light_llm_input_price_per_mtok,
        light_llm_output_price_per_mtok=settings.light_llm_output_price_per_mtok,
```

（注意 fake 分支下 `light_llm` 变量未定义——在 `if settings.fake_mode:` 分支同排位加 `light_llm = None`。）

- [ ] **Step 1.7: 导入自检**

Run: `uv run python -c "from quanta_bot.composition import build_runtime; from quanta_bot.infra.settings import Settings; build_runtime(Settings())"`
Expected: 无异常。

---

### Task 2: 批量核心测试 + eval case + 门禁接线

- [ ] **Step 2.1: 一次性写完全部核心测试**

`tests/unit/crosscutting/test_budget.py` 追加：

```python
# ---- M5-B 分档判定（阈值边界 + fail-open）----

from datetime import date

from quanta_bot.infra.kv import InMemoryKV


class ExplodingGetKV(InMemoryKV):
    """get 必抛的 kv（fail-open 数据源）。"""

    async def get(self, key: str) -> str | None:
        raise RuntimeError("redis 挂了（测试注入）")


async def test_read_tier_thresholds() -> None:
    """分档边界：19999 充足 / 20000 吃紧 / 27999 吃紧 / 28000 枯竭（厘）。"""
    kv = InMemoryKV()
    day = date(2026, 9, 25)
    for cost_li, expected in [
        (19999, "sufficient"),
        (20000, "tight"),
        (27999, "tight"),
        (28000, "exhausted"),
    ]:
        await kv.set(budget.cost_key(day), str(cost_li), ttl_seconds=3600)
        tier = await budget.read_tier(kv, day, tight_threshold_li=20000, exhausted_threshold_li=28000)
        assert tier == expected, f"{cost_li} 厘应为 {expected}"


async def test_read_tier_fail_open() -> None:
    """成本键读失败 → 按充足档继续（fail-open，WARNING 留痕）。"""
    tier = await budget.read_tier(
        ExplodingGetKV(), date(2026, 9, 25), tight_threshold_li=20000, exhausted_threshold_li=28000
    )
    assert tier == "sufficient"
```

`tests/unit/pipeline/test_pipeline.py` 追加（复用 A 区的 `_mention_event`）：

```python
# ---- M5-B 成本分档：枯竭静默 / 吃紧切生成 / 写失败 fail-open ----


class NoIncrementKV(InMemoryKV):
    """increment 必抛的 kv（成本写失败 fail-open 数据源）。"""

    async def increment(self, key: str, amount: int, ttl_seconds: int) -> int:
        raise RuntimeError("redis 写挂（测试注入）")


async def test_cost_exhausted_silent_skip(tmp_path) -> None:
    """日成本达枯竭阈值 → skipped_cost_exhausted 静默不回，LLM 零调用。"""
    llm = FakeLLM(responses=[_DECISION_JSON, "不该被生成"])
    deps = _m3_deps(llm=llm, tmp_path=tmp_path)
    await deps.kv.set(
        budget.cost_key(datetime.now(UTC).date()), "28000", ttl_seconds=3600
    )
    decision = await run(_mention_event(301), deps)
    assert decision == "skipped_cost_exhausted"
    assert llm.calls == []  # 枯竭=任何 LLM 调用不发


async def test_cost_tight_generation_switches_to_light_model(tmp_path) -> None:
    """吃紧档只切生成：决策留主模型、生成走轻模型；成本按轻模型单价折算并留痕。"""
    tracer = SpyTracer()
    main_llm = FakeLLM(responses=[_DECISION_JSON])
    light_llm = FakeLLM(default_content="轻模型降级回复", model_name="qwen-test")
    deps = _m3_deps(llm=main_llm, tracer=tracer, tmp_path=tmp_path)
    deps.light_llm = light_llm
    await deps.kv.set(
        budget.cost_key(datetime.now(UTC).date()), "20000", ttl_seconds=3600
    )
    decision = await run(_mention_event(302), deps)
    assert decision == "replied"
    assert len(main_llm.calls) == 1  # 决策一车四用留主模型
    assert len(light_llm.calls) == 1  # 生成切轻模型
    trace = tracer.traces[-1]
    assert trace.model_used == "qwen-test"
    assert trace.cost_tier == "tight"
    # FakeLLM tokens 500/100 → (500*0.8 + 100*2.0)/1e6 元 = 0.6 厘 → 取整 1
    assert trace.cost_li == 1


async def test_cost_tight_without_light_llm_falls_back_to_main(tmp_path) -> None:
    """轻模型未配置 → 吃紧档回退主模型照常回复。"""
    tracer = SpyTracer()
    deps = _m3_deps(
        llm=FakeLLM(responses=[_DECISION_JSON, "主模型兜底回复"]), tracer=tracer, tmp_path=tmp_path
    )
    deps.light_llm = None  # 未配置
    await deps.kv.set(
        budget.cost_key(datetime.now(UTC).date()), "20000", ttl_seconds=3600
    )
    decision = await run(_mention_event(303), deps)
    assert decision == "replied"
    assert tracer.traces[-1].model_used == "fake-llm"


async def test_add_cost_failure_does_not_fail_reply(tmp_path) -> None:
    """成本键累加失败 → fail-open 照常回复（对账可见缺口，不 failed）。"""
    deps = _m3_deps(
        llm=FakeLLM(responses=[_DECISION_JSON, "回复"]),
        kv=NoIncrementKV(),
        tmp_path=tmp_path,
    )
    decision = await run(_mention_event(304), deps)
    assert decision == "replied"
```

- [ ] **Step 2.2: eval case + runner + manifest**

`tests/eval/_runner.py`：`EvalCase` 增字段（`breakers_open` 之后）：

```python
    cost_preset_li: int = 0  # M5-B：预置当日成本键（厘）——枯竭/吃紧档演练
```

`run_case` 预置区（`breakers_open` 循环之后）加：

```python
    if case.cost_preset_li:  # M5-B：预置当日成本键（档位判定数据源）
        await deps.kv.set(
            budget.cost_key(datetime.now(UTC).date()), str(case.cost_preset_li), 3600
        )
```

（import 区补 `from quanta_bot.crosscutting import budget` 与 `datetime`。）

Create `eval/cases/pipeline-cost-exhausted.yaml`：

```yaml
# 成本枯竭 → 静默不回（skipped_cost_exhausted）：预置当日成本键 28000 厘（枯竭阈值），
# 决策 LLM 不应被调用——不花钱的闸门挡在一切 LLM 调用之前。
id: pipeline-cost-exhausted
scenario: 0
scenario_name: 成本枯竭静默不回
mode: 专业答疑
tier: pipeline
memories: []
cost_preset_li: 28000
trigger:
  post: { postId: 2102, userId: 1, title: 成本演练帖, content: "预算还够吗" }
  comment: { commentId: 9102, userId: 42, content: "@框框 帮我看看这题" }
llm_script:
  - '{"should_reply": true, "mode": "专业答疑", "confidence": 0.9, "reason": "求助"}'
  - "若分档失效被调用，此回复不应出现"
deterministic:
  - { assert: decision_is, expected: skipped_cost_exhausted }
judge: null
```

`eval/gate-manifest.yaml` 的 `fast_gate_ids` 追加 `pipeline-cost-exhausted`。

- [ ] **Step 2.3: 批量验证**

```
uv run ruff format src tests
uv run ruff check src tests
uv run pytest tests/unit -q
uv run pytest tests/eval -q
```
Expected: 全绿（新增 budget 2 测 + pipeline 4 测 + eval 1 case；若 FakeLLM 构造签名变化破坏既有测试，按新签名补 model_name 默认参数即可——默认值保持既有行为）。

- [ ] **Step 2.4: Commit**

```bash
git add QuantaBot/src QuantaBot/tests QuantaBot/eval QuantaBot/.env.example
git commit -m "feat(cost): M5-B 成本分档全量——三档判定+轻模型路由+fail-open 记账

影响面：日成本 20 元起生成切 Qwen3.5-Plus（决策/摘要保主模型），28 元起枯竭静默不回(skipped_cost_exhausted)；成本键读写失败均 fail-open。RunTrace 增 cost_tier/model_used。"
```

---

### Task 3: 文档回写

- [ ] **Step 3.1: 技术选型.md §4.7**：回填最终阈值（吃紧 20000 厘/枯竭 28000 厘）、轻模型身份（Qwen3.5-Plus，输出单价部署前核对注记）、「吃紧只切生成、枯竭静默」行为矩阵、轻模型未跑 Persona eval 的已知风险（Maintain 观察项）。
- [ ] **Step 3.2: 总计划.md**：勾选 M5-成本分档全量 checkbox；§7 变更记录追加 M5-B 条目（含「轻模型暂不跑 Persona eval——已知风险留档」）。
- [ ] **Step 3.3: Commit**

```bash
git add QuantaBot/docs QuantaBot/总计划.md
git commit -m "docs: M5-B 成本分档回写（阈值口径/轻模型身份/已知风险留档）"
```

---

## 验收标准

1. `uv run pytest tests/unit tests/eval -q` 全绿（新增 6 测 + 1 eval case 进 fast_gate）；
2. Fast gate 通过；
3. 分档行为可查：枯竭 `skipped_cost_exhausted`、吃紧 `model_used` 留痕、fail-open 不阻断——均有断言；
4. `build_runtime` 真模式缺轻模型配置时 WARNING 降级（装配自检可观察）；
5. 技术选型/总计划回写完成。
