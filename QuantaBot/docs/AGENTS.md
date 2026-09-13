# AGENTS.md — QuantaBot（AI 评论智能体）

> 本文件是 AI 编码智能体在本仓库的"操作说明书"（AGENTS.md 开放标准）。
> 关联文档：`PRD-QuantaBot.md`（需求与场景）、`技术选型.md`（技术基线，**唯一权威决策来源**，v1.5）。
> 状态：**v0.3 执行版**——架构/红线/技术栈/目录结构/代码规范/工作流已定稿；未标 `[待确认]` 的内容为已确定项，实现时不得臆造冲突；`[待确认]` 项依赖 Phase 0 契约对齐，确认前不要臆造实现。

***

## 0. 最高优先级（不可违背）

> 以下规则优先级高于一切，任何实现细节不得与之冲突。

1. **AI 身份透明**：每条 AI 回复必须明确标注 AI 身份（昵称/头像/AI 标识），绝不冒充真人校友。
2. **不抢生态**：AI 的回答永远不能被采纳为"最佳回答"，只做补充。
3. **静默优于乱回**：模型不可用、超时、审核不过时，选择"不回"，绝不硬回。
4. **上线前置条件**：输出审核 + 幂等防重必须从第一条回复就存在，不是后补项。
5. **写库走主服务**：AI 回复统一走主服务写库入口，复用鉴权与数据校验，禁止直连数据库绕过业务层。
6. **红线场景**：涉及真实伤害/违法内容，AI 明确不接并引导求助。

***

## 1. 项目背景（一行版）

QuantaBot：校园校友社区 QuantaCommunity 上的 AI 互动账号，被动 @ 触发（MVP）、四表达模式（专业答疑/生活玩梗/情绪陪伴/治理）、人格一致性 = 内核不变 + 模式切换正确。场景清单与指标见 `PRD-QuantaBot.md`；**技术栈/架构/目录/部署细节一律读** **`技术选型.md`（唯一权威），本文件不重复维护副本。**

- **代码仓库**：QuantaCommunity 全家桶（demo0 主服务 / QuantaBot / demo0-admin / demo0-miniprogram）托管于 GitHub：`https://github.com/stephen688/QuantaCommunity`；本仓库（QuantaBot）对应其中 `QuantaBot/` 目录。
- **宿主主服务**：`../demo0`（Java 21 / Spring Boot / Maven）——事件发出 + 结果写库 + 检索/评论树接口，本仓库不重复实现，**禁止改动 demo0 代码**（除非明确授权）。

***

## 2. 目录结构（骨架；文件级明细见 `技术选型.md` §10.1）

```
QuantaBot/
├─ src/quanta_bot/          # 服务主体（分层：pipeline/memory → crosscutting → infra）
│  ├─ server.py             #   FastAPI 入口（/health、管理端点）
│  ├─ consumer.py           #   MQ 消费者主循环（单消费者；同帖串行的宿主）
│  ├─ composition.py        #   装配根（全仓库唯一可 import 一切的模块）
│  ├─ pipeline/             #   核心链路，按数据流排列（业务，常改）
│  │  └─ trigger / decision / context / generation / pipeline
│  ├─ memory/               #   记忆层：接口在此，实现走 infra
│  │  └─ ports.py + dialogue / user_memory
│  ├─ crosscutting/         #   横切纪律：全纯逻辑，重点单测
│  │  └─ idempotency / breaker / rate_limit / budget / killswitch / moderation
│  └─ infra/                #   唯一碰外部世界的层
│     └─ settings / main_service / mq / vector / kv / audit_db / tracing
├─ prompts/                 # 人格文件，git 版本化（是数据不是代码）
├─ eval/                    # 评测集 YAML
├─ tests/
│  ├─ unit/                 #   单测（镜像 src 结构）
│  └─ eval/                 #   评测门禁 runner
├─ docker/                  # Dockerfile + compose
└─ docs/                    # PRD / 技术选型 / AGENTS.md
```

**分层规则（硬规则）**：`pipeline/memory → crosscutting → infra`，禁止反向 import；唯一例外 `composition.py`。`prompts/` 是数据不是代码，generation 只读不内联 prompt 字符串。

**禁止改动**：`../demo0`、`../demo0-miniprogram`、`../demo0-admin` 的代码（除非明确授权）。

***

## 3. 命令

- **依赖管理**：`uv sync --frozen`（按锁文件安装，CI/他人机器一律加 `--frozen`）/ `uv add <pkg>`（更新 uv.lock 后提交）。
- **本地运行**：`uv run uvicorn quanta_bot.server:app --reload`（`[待确认]`：端口/配置项等联调细节，Phase 0 对齐后补充）。
- **单测**：`uv run pytest tests/unit -q`。
- **评测门禁**：`uv run pytest tests/eval`（改人格/策略后必须通过才能提交发布；种子 = PRD 7.5 三个验收样例）。
- **promptfoo 红队**：`promptfoo redteam run --config eval/redteam.yaml`（独立 CLI，不进 Python 依赖树；报告进发布门禁）。
- **lint/格式化**：`uv run ruff check src tests` + `uv run ruff format src tests`（ruff 同时承担 lint 与 format，不引入 black/isort）。
- **与主服务联调**：`[待确认]` — 需主服务 Phase 0 对齐后补充（MQ 队列名、接口地址/鉴权方式、评论树接口契约）。

***

## 4. 代码风格与规范

### 4.1 分层纪律（硬规则，违反即打回）

- 依赖方向：`pipeline/memory → crosscutting → infra`，禁止反向 import；唯一例外 `composition.py`（装配根，可 import 一切）。
- 配置一律从 `infra/settings.py` 的 `Settings` 读；业务模块禁止直接触碰 `os.environ`。
- 外部客户端（httpx/qdrant/redis/aio-pika）只在 `composition.py` 创建后注入；禁止模块顶层建连接、禁止业务代码自建客户端。
- 业务/横切逻辑不写裸 SQL、不直接 import qdrant-client——记忆操作走 `memory/ports.py` 接口，评测时可换内存假实现。

### 4.2 模块规范

- 每个文件头部注释：模块名 + 职责 + 边界（"本模块不负责什么"）。
- 已知坑/例外必须写进头部注释（例："本模块依赖运行时注解，禁用 `from __future__ import annotations`"这类）——坑写在使用现场，不靠口口相传。
- docstring 面向使用者写行为契约（做什么、何时调用、何时不调用），不写给作者看的废话。
- 注释解释"为什么 / 边界 / 例外"，不复述代码；中文注释，标识符用英文。

### 4.3 Python 具体规则

- **类型注解**：公开函数/接口/契约模型一律注解；Pydantic 模型即主服务接口契约的文档。
- **async 全链路**：路由/工具/IO 全部 async；禁止在 async 函数里调用同步 IO（requests、阻塞 DB 驱动等）。
- **异常**：只捕获能处理的异常类型；处理不了的让它炸到熔断层静默——禁止裸 `except:`、禁止"吞异常返回 None"伪装成功（违反诚实口径红线）。
- **决策点必须打点**：每个"回 / 不回 / 降级"分支写决策日志（Langfuse trace + SQLite），口径与 PRD F6 一致。
- **日志**：`logging` 标准库 + `logger = logging.getLogger(__name__)`；关键分支 INFO，外部依赖失败 WARNING（含降级动作），静默丢弃的触发必须留下 DEBUG 以上痕迹。
- **命名**：模块/函数 snake\_case，类 PascalCase，常量 UPPER\_SNAKE；不搞单字母变量（循环索引除外）。

### 4.4 工具

- ruff 同时承担 lint 与 format（`ruff check` + `ruff format`），不引入 black/isort/flake8。
- 格式化不是可选项：提交前 `uv run ruff format src tests` 必须跑。

***

## 5. Do / Don't（已确定项）

**Do**

- 回复一律走主服务写库入口（复用鉴权与校验），禁止直连数据库。
- 幂等去重以 **comment\_id** 为键（不是 message id；message id 仅投递层辅助）。
- 同帖消息按 post\_id 串行处理，组装上下文带"触发前最新楼层快照"防穿越。
- AI 回复前置内容审核（规则预检 + 主服务审核），违规一律不出。
- 记录每条触发的决策：为什么回 / 为什么没回（进 Langfuse trace + SQLite 明细）。
- 决策日志统计口径要诚实：模型超时/审核拦截的回复不算"成功回复"。
- LLM 调用前读 Redis 日累计成本键分档（充足→主模型 / 吃紧→轻模型 / 枯竭→规则兜底不调模型）。
- 模型/审核/记忆读写分设超时档与三态熔断，超时静默不回。
- kill switch / 灰度白名单 / 人格版本走 Redis 配置键，Agent 轮询 ≤5s。

**Don't**

- 不绕过主服务直连数据库写回复。
- 不在人格/策略变更后沿用旧口径记忆而不做处理（Qdrant payload 用 persona\_version 标记失效）。
- 不实现"全链路一个超时"。
- 不把主动触发做成无节制的定时轰炸（必须有时序 + 频率 + 总量配额）。
- 不引入 LangChain/LangGraph 等编排框架（自组链路）。
- 不把决策日志双写主服务 MySQL（用 Langfuse + SQLite）。

***

***

# 6. 编码工作流（一个任务从开始到结束的动作序列）

> 核心闭环：**动手前规划与阅读 → 写码中最小改动 → 完成后验证与交付**（对齐主流 AGENTS.md 实践）。任何任务都必须走完这个闭环；以下为硬性动作序列。

### 6.1 动手前（Before coding）

1. **加载上下文**：先读本文件 §0 红线与 §5 Do/Don't；涉及需求读 `PRD-QuantaBot.md`；涉及架构/接口/契约读 `技术选型.md` 对应章节（唯一权威）。
2. **看 git 状态**：确认当前分支与工作区，识别已有改动；不得覆盖他人/其他任务的未提交修改。
3. **写计划**：先给 1–2 句方案说明；非平凡任务（≥3 步或涉及架构决策）列出编号步骤，每步带验证项。
4. **先读再改**：读将要修改的文件及其调用方（上游/下游），理解现状后再决定怎么改，不靠猜。
5. **跟随现有风格**：项目已有模式 X 就用 X，不因个人偏好换新模式。
6. **歧义先问**：需求可做两种以上合理解读、或改动属高风险（删文件/动 demo0/动契约）时，先与用户确认，不自行假设直接执行。

### 6.2 写码中（While coding）

1. **最小正确改动**：只改需求要求的部分；不顺手重构、不格式化无关代码、不"顺便修"别处（发现别处问题 → 记入交付摘要，不混入本次改动）。
2. **不编造**：不编造接口字段、数据结构、测试结果、工具能力；拿不准就查代码/文档，或明说"需要确认"。
3. **遵守分层硬规则**：`pipeline/memory → crosscutting → infra`，禁止反向 import；外部客户端只在 `composition.py` 创建后注入。
4. **决策点打点**：每个"回/不回/降级"分支写决策日志（Langfuse trace + SQLite），口径与 PRD F6 一致。
5. **注释与文档同步**：保留仍有效的注释；行为变化时同步修正对应文档（决策变化先改 `技术选型.md`）。

### 6.3 完成后（After coding）

1. **验证三件套（必跑）**：`uv run ruff format src tests` + `uv run ruff check src tests` + `uv run pytest tests/unit -q`。
2. **评测门禁**：改人格/策略/审核/幂等逻辑 → 必须 `uv run pytest tests/eval` 通过 + `promptfoo redteam run --config eval/redteam.yaml` 报告；不达标不得提交发布。
3. **自查 diff**：每行改动都能对应需求；删除自己引入的孤儿代码（未用 import/变量/函数）；diff 里没有与任务无关的变更。
4. **文档回写**：消灭本次涉及到的 `[待确认]`（或按 Phase 0 结论更新）；决策变化先改 `技术选型.md`（唯一权威）再同步本文件；本文件追加变更记录。
5. **提交**：Conventional Commits（`feat/fix/docs/refactor/test/chore`）；横切保障相关改动（幂等/熔断/kill switch）提交信息必须注明影响面，方便回滚定位。
6. **交付摘要**：说明改了什么、验证证据（命令实际输出，不口报"应该过了"）、未完成事项；未经授权不 push。

### 6.4 Git 约定

- **分支**：`main`（可运行基线）+ 功能分支 `feat/<模块>-<一句话>`（如 `feat/trigger-mq-consumer`）、修复分支 `fix/<现象>`；分支生命期 ≤3 天，防长分支漂移。
- **提交频率**：每完成一个可独立验证的小块就提交一次，不攒大 commit。
- **文档冲突处理**：本文件与《技术选型.md》冲突时，以《技术选型.md》为准并尽快修正本文件。

### 6.5 Phase 0 契约落地（`[待确认]` 回填机制）

- 与主服务 Owner 的对齐结论（检索/评论树/写库/审核/MQ 事件/政策内容源/Redis 写权限）拿到后，回填本文件 §3 联调命令与 `infra/settings.py` 环境变量清单。
- 契约确认前不臆造：`[待确认]` 项用 TODO + 引用占位，不得编造契约字段。

***

