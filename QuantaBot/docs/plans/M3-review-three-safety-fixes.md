# M3 Review Three Safety Fixes Implementation Plan

> **For Codex:** 使用 `test-driven-development` 逐项执行；每项先看到目标测试按预期失败，再写最小实现。用户已于 2026-09-18 明确要求“马上修复”，本文件即本轮计划确认记录。

**Goal:** 修复 M3 收尾审查确认的三条安全缺陷：摘要网络失败不降级、空生成写入徽章空壳、真模式缺主服务时摄取合成政策。

**Architecture:** 不新增抽象。摘要异常在 C 通道边界转为既有“丢远区照常回复”；空输出在生成边界转为既有 `LLMClientError → pipeline failed`；RAG 在真模式缺主服务时保留已有索引检索，但关闭摄取栈使 `/admin/ingest` 返回 503。

**Tech Stack:** Python 3.13、pytest、Pydantic、FastAPI、Qdrant、ruff。

## 需求结论

- 依据：`AGENTS.md` §0.3、§4.3、§6.2；`总计划.md` M3 上下文分通道；`docs/plans/M3-场景人格记忆.md` 全局约束。
- 范围：只修三条已确认缺陷及其回归测试，不改 demo0，不新增重试、熔断或额外防护。
- 失败语义：摘要失败只丢远区；生成空内容整条静默失败；无真实主服务时禁止主动摄取，但已存在的 Qdrant 内容仍可检索。
- 成功标准：三条回归测试先红后绿；unit、默认 eval、ruff 全绿；独立 reviewer 无未处理 Critical/Important。

## Task 1：摘要传输失败降级

**Files:**
- Modify: `tests/unit/pipeline/test_context.py`
- Modify: `src/quanta_bot/pipeline/context.py`

1. 增加 `LLMClientError` 回归测试，断言 C 通道返回空摘要、`from_cache=False` 且有“远区丢弃”留痕。
2. 运行目标测试，确认异常当前会逃逸。
3. 在 `build_channel_c` 仅扩展可处理异常为 `(SummaryError, LLMClientError)`。
4. 运行目标测试与 `tests/unit/pipeline/test_context.py`。

## Task 2：拒绝空生成内容

**Files:**
- Modify: `tests/unit/pipeline/test_generation.py`
- Modify: `src/quanta_bot/pipeline/generation.py`

1. 增加空白 LLM 输出测试，期望抛出 `LLMClientError`。
2. 运行目标测试，确认当前生成徽章空壳而失败。
3. 在补徽章前校验 `strip()` 后内容；为空则抛 `LLMClientError`，交由现有 pipeline failed 分支处理。
4. 运行目标测试与 generation 单测。

## Task 3：真模式隔离合成摄取源

**Files:**
- Modify: `tests/unit/test_composition.py`
- Modify: `src/quanta_bot/composition.py`

1. 将现有“缺主服务使用 FakeContentSource”测试改为安全契约：`runtime.rag is None`，但 `deps.retriever` 仍为 `QdrantContentIndex`。
2. 运行目标测试，确认当前仍装配合成源。
3. 真模式 qdrant+embedding 齐但主服务缺失时只装配 retriever，记录 WARNING，并保持 `rag_stack=None`。
4. 运行 composition/server 相关单测。

## Task 4：全量验证与交付

1. 运行 `uv run ruff format src tests`。
2. 运行 `uv run ruff check src tests`、`uv run pytest tests/unit -q`、`uv run pytest tests/eval -q`。
3. 自查 diff；派发独立 reviewer，修复 Critical/Important。
4. 分块提交，review 通过后本地合并到 `main`；未经授权不 push。
