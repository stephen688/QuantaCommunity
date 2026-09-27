# M5 Persona 门禁校准 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking. 本机未安装这两个执行技能，使用现有 collaboration 子agent工具执行，TDD与独立review按AGENTS保持。

**Goal:** 修复74b6463双轮暴露的格式P0与表达P1/P2问题，保留失败证据，在新SHA重跑同标准Release。

**Architecture:** 不改案例或Judge阈值。生成层校验显式问号数量，最多进行一次格式修复生成；仍违规静默失败，绝不改标点伪装合格。两次已返回usage都纳入已知生成估算；格式校验最终失败也保留已知费用。人格数据只补通用的事实归因与自然表达约束，不抄用例答案。

**Tech Stack:** 现有Python async/ports、pytest、ruff、DeepSeek Flash及Promptfoo 0.123.1。

**Spec:** 用户2026-09-26明确要求“按最小改动修复，保留失败证据，再冻结新 SHA 重跑门禁”；`AGENTS.md` §6.5格式护栏升级条件；`eval/reports/m5-release-74b6463/persona/m4-v1-summary.json`。

## Global Constraints

- 不改demo0/前端、不改17用例/断言/Judge阈值、不换模型；Qwen首次超时费用缺口继续暂缓。
- 一次回复最多一个问题；格式护栏负责已观察到的显式问号超限，不宣称能理解所有无问号的隐式连环追问。
- 事实只能来自上下文或真实检索；不能为“补充事实”臆造新信息；未知或传闻必须归因并提供可执行核实方向。
- 内部提示索取的现有固定拒绝分支不变，不追加模型调用。
- 不删除/覆盖74b6463失败报告。新一轮结果使用新SHA独立目录；raw不入Git。

### Task 1: 生成显式提问格式护栏（子agent）

**Files:** `src/quanta_bot/pipeline/generation.py`、必要时`pipeline/ports.py`/`pipeline.py`、`infra/deepseek.py`/`infra/tracing.py`；`tests/unit/pipeline/test_generation.py`及新增窄回归测试。对调用未返回用量的必要诚实口径，允许最小拓展指标/对账脚本及其测试。不修改人格prompts或冻结eval。

**Interfaces:** `generate(event, decision, llm, context_text, persona)`保持原签名；`GenerationOutput`继续提供回复及已知usage；失败走`LLMClientError`继承契约。

- [x] 先写并跑RED：模型首次返回“你今晚就要交？能等到明天吗？”、次次返回“一次先确认截止时间，你今晚必须交纸质版吗？”；实际generate只能返回第二个完整回复，usage为两次之和；不改写标点偷过。
- [x] 先写并跑RED：连续两次超限，不能返回或写库；只允许两次生成，不无限刷分；已知usage在失败管线的cost_li留存。
- [x] 最小实现：问号数`sum(content.count(mark) for mark in ('?', '？'))`，>1时仅追加通用system格式校正指令重新生成一次，不回灌上一条模型原文；仍违规抛契约错误并由管线静默。补正常零/一问题、固定拒绝不调用、空输出及usage等回归。
- [x] 二次调用未返回usage时，仍累计首笔已知费用，根观测明确用量不完整，指标/严格对账不将已知部分误当完整费用。
- [x] 连续格式违规/空输出在 `scripts/run_m4_gate.py` 中结构化归为能力失败，不得按基础设施故障重跑；`tests/eval/test_gate_cli.py` 固化分类、调用次数与失败已知费用。
- [x] 独立review I-1：供应商HTTP成功但缺usage同样标不完整；从LLMResult经生成结果传递到根trace，两笔生成不能把缺失笔误认为零费用完整。3条新回归RED/GREEN，窄58 passed，最终Fast306 passed。
- [x] 窄回归45 passed、全unit236 passed后再补必要账本/分类回归；最终Fast303 passed（含全unit），ruff通过，独立review前未commit。详见 `eval/reports/m5-calibration-free-verification.md`。

### Task 2: 通用人格约束校准（主agent）

**Files:** `prompts/kernel.md`、`prompts/mode_banter.md`、`prompts/mode_emotion.md`；不改案例、评分器。

- [x] 读取首轮原始失败证据，区分真实缺口与评分偏差，写脱敏分析。结果见 `eval/reports/m5-release-74b6463/persona-failure-analysis.md`。
- [x] 补充通用约束：AI身份由徽章透明标识，正文直接回应用户而非自报角色/解释任务；补充信息必须归因上下文，不把未核实传闻说成事实，未知时给具体核实动作而非仅说缺资料。
- [x] 跑免费门禁，确认人格加载、四模式与所有pipeline断言仍正确；行为质量由下一轮真模型双跑裁决，不用源码文本断言伪造语义测试。

### Task 3: Review、冻结与真实Release（主agent协调）

- [x] 依requesting-code-review做独立只读review，C=0/I=0/Minor=0后精确提交实现与校准计划，冻结新SHA。
- [x] 派两个agent并行Persona17×2和红队75，使用相同新SHA、同模型同阈值；基础设施只按原runner规定重试，不能能力失败刷轮次。冻结SHA `9c29bf2`：Persona34/34通过；红队75项执行结束，自动73通过/2失败，评审有效性待补证，不宣称Release通过。
- [x] 回填实际pass/fail/error、usage估计、hash与SHA。Persona通过；红队为 `EVALUATION_BLOCKED`（75项执行完成、73自动通过、2无有效判据的评审异常），保留自动失败及raw证据，不人工改PASS。M5上线/评测终验仍未勾选，成本缺口单独保留暂缓。详见 `eval/reports/m5-final-acceptance.md` 与新SHA脱敏摘要。

### Task 4: 两项评审异常定向补证（2026-09-27，用户授权继续）

- [x] 只读核实锁定Promptfoo的原始grader调用、冻结rubric/context与实际变形input/output；索引仅31/36，raw指纹匹配，不调用attacker或重新生成目标输出。明确区分seed与最终input，原rubric使用最终input。
- [x] 同模型同标准各一次grader-only重评，2/2 valid PASS、score1；2请求/0缓存，3024token，原raw未变。新raw受限保存，未返回费用不补零。
- [x] 独立复核补证范围、证据指纹及有效PII判据，C=0/I=0/Minor=0；合并73项原始通过为PASS_WITH_TARGETED_GRADER_RECHECK，原73/75与BLOCKED报告不覆盖。不重跑Persona或整轮红队、不改冻结产品；严格成本缺口继续暂缓。
