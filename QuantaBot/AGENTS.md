# AGENTS.md — QuantaBot（AI 评论智能体）

> 本文件是 AI 编码智能体在本仓库的"操作说明书"（AGENTS.md 开放标准）。
> 关联文档：`PRD-QuantaBot.md`（需求与场景）、`技术选型.md`
> 变更记录：v0.4（2026-09-13）——移动至 QuantaBot/ 根；硬规则去重（一处定义他处引用）；补 git 语境/分支模型/大功能判据；§6.3 增独立审查与步内 TDD 节拍；修复反向引用与格式。
> v0.4.1（2026-09-13）——§6.0.1 总计划.md 状态由"待创建"改为"已创建 v1.0"（总计划落盘后同步回写）。
> v0.4.2（2026-09-13）——§3 本地运行消除端口 [待确认]：端口是 Agent 自有服务配置（默认 8000，--port 可覆盖），非主服务联调契约；联调配置项仍随 Phase 0 结论更新。
> v0.4.3（2026-09-16）——M2 完成：§3 增「本地全栈」「integration 显式档」命令；「与主服务联调」[待确认] 按 Phase 0 结论（C-1~C-7）落为契约级事实（真联调回补随 demo0 D1-D7）。
> v0.4.4（2026-09-16）——§4.3 硬规则收紧两条：①命名禁止单字母/过度简化变量（循环索引除外）；②核心业务链路每一步必须跟简短行内注释说明业务意图（M2 review 反馈）。

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
└─ docs/                    # PRD / 技术选型 / AGENTS.md / plans/
```

**分层规则（硬规则）**：见 §4.1（依赖方向唯一权威定义）。`prompts/` 是数据不是代码，generation 只读不内联 prompt 字符串。

**禁止改动**：`../demo0`、`../demo0-miniprogram`、`../demo0-admin` 的代码（除非明确授权）。

***

## 3. 命令

- **依赖管理**：`uv sync --frozen`（按锁文件安装，CI/他人机器一律加 `--frozen`）/ `uv add <pkg>`（更新 uv.lock 后提交）。
- **本地运行**：`uv run uvicorn quanta_bot.server:app --reload`（默认端口 8000，`--port` 可覆盖——端口是 Agent 自有服务配置，非主服务联调契约；联调相关配置项随 Phase 0 结论更新）。
- **单测**：`uv run pytest tests/unit -q`。
- **本地全栈（M2 起）**：`docker compose -f docker/docker-compose.yml up -d`（app+qdrant+dev RabbitMQ+agent-redis+Langfuse v4 全家桶，共 10 容器；资源 12GB+）。
- **integration 显式档（真依赖 smoke）**：`QUANTABOT_INTEGRATION=1 uv run pytest tests/integration`（默认 skip；真调 DeepSeek/Langfuse/Redis/MQ/Qdrant，LLM 产生真实计费；跑前 `docker compose stop app` 防消费者抢消息，跑完恢复）。
- **评测门禁**：`uv run pytest tests/eval`（改人格/策略后必须通过才能提交发布；种子 = PRD 7.5 三个验收样例）。
- **promptfoo 红队**：`promptfoo redteam run --config eval/redteam.yaml`（独立 CLI，不进 Python 依赖树；报告进发布门禁）。
- **lint/格式化**：`uv run ruff check src tests` + `uv run ruff format src tests`（ruff 同时承担 lint 与 format，不引入 black/isort）。
- **与主服务联调**（Phase 0 已对齐 C-1~C-7，契约级等价物已落地）：MQ 队列 `quantabot.comment.queue`（C-1 消息契约=BotMentionMessage）；评论树 GET `/bot/comment/chain|history`、写库 POST `/comment/send`（C-2/C-5，Bearer service token）；**端到端真联调随 demo0 D1-D7 回补**（M2 计划 Task 19）。
- **从零初始化** `[待确认]`：`.env` 从 `.env.example` 复制（settings.py 定稿后提供模板）、promptfoo 独立 CLI 安装步骤、红队通过标准 → 见《技术选型.md》评测阈值细节节；eval/ 落地后回填本条。

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
- **命名**：模块/函数 snake\_case，类 PascalCase，常量 UPPER\_SNAKE；**禁止单字母/过度简化变量名（循环索引 i/j/k 除外）**——如 `d`、`t`、`r` 一律打回，须用完整含义的名称（如 `decision`、`thread`、`result`）。命名既要见明知意，又不能过长。
- **核心业务步骤必须行内注释**：核心业务链路（如 pipeline 各关卡、决策/生成/写库路径）**每一步后面跟一条简短注释**说明该步业务意图（`# ① 幂等：重复投递静默跳过` 这类），不靠读者推断；纯机械代码不强制。

### 4.4 工具

- ruff 同时承担 lint 与 format（`ruff check` + `ruff format`），不引入 black/isort/flake8。
- 格式化不是可选项：提交前 `uv run ruff format src tests` 必须跑。

***

## 5. Do / Don't（已确定项）

**Do**

- 幂等去重以 **comment\_id** 为键（不是 message id；message id 仅投递层辅助）——幂等机制见 §0.4。
- 同帖消息按 post\_id 串行处理，组装上下文带"触发前最新楼层快照"防穿越。
- 记录每条触发的决策：为什么回 / 为什么没回——打点要求见 §4.3。
- 决策日志统计口径要诚实：模型超时/审核拦截的回复不算"成功回复"。
- LLM 调用前读 Redis 日累计成本键分档（充足→主模型 / 吃紧→轻模型 / 枯竭→规则兜底不调模型）。
- 模型/审核/记忆读写分设超时档与三态熔断，超时静默不回。
- kill switch / 灰度白名单 / 人格版本走 Redis 配置键，Agent 轮询 ≤5s。

**Don't**

- 不绕过主服务直连数据库写回复（= §0.5 的反写，勿绕过写库入口）。
- 不在人格/策略变更后沿用旧口径记忆而不做处理（Qdrant payload 用 persona\_version 标记失效）。
- 不实现"全链路一个超时"。
- 不把主动触发做成无节制的定时轰炸（必须有时序 + 频率 + 总量配额）。
- 不引入 LangChain/LangGraph 等编排框架（自组链路）。
- 不把决策日志双写主服务 MySQL（用 Langfuse + SQLite）。

***

## 6. 编码工作流（一个任务，一个计划文件从开始到结束的动作序列）

> 核心闭环：**计划阶段（读总计划 → grill-me 对齐 → writing-plans 写计划）→ 动手前规划与阅读 → 写码中最小改动 → 完成后验证、审查与交付**（对齐主流 AGENTS.md 实践）。任何任务都必须走完这个闭环；以下为硬性动作序列。

### 6.0 计划阶段（Planning；新任务/大功能必经）

> 触发判据（可操作）：满足任一即走本节——① 改动跨 ≥2 个分层（pipeline/memory、crosscutting、infra）；② 新增模块/公开接口/契约字段；③ 预计产出超过 1 个计划文件或改多人共有的文件。仅 typo/单点 bug/纯文档措辞/单文件小修 → 跳过本节直接进 6.1。

1. **读总计划**：先读 `总计划.md`（项目根，**已创建 v1.0**）——项目级路线图（阶段划分/任务依赖/里程碑与进度看板）。确认本次任务在总计划中的位置与前后依赖，明确"这一刀切在哪"。
2. **grill-me 对齐核心需求**：用 `grill-me` skill（Matt Pocock 版）对本次任务做需求拷问——一次只问一个问题、沿决策树逐分支走、每个问题附推荐答案、能从代码库/文档查到答案的自己查不问人；直到 scope（做什么/明确不做什么）、数据流、失败模式、成功标准全部达成共同理解。对齐结论落到 `docs/plans/<任务>.md` 的"需求结论"节。
3. **writing-plans 写计划**：用 `superpowers:writing-plans` skill（obra/superpowers 版）把对齐后的需求写成可执行计划——任务拆到 2-5 分钟可完成一个、每步带精确文件路径与验证项；计划落盘 `docs/plans/<任务>.md`，不只在对话里说。
4. **计划确认门**：计划交用户确认后才能进入 6.1；执行中计划有变，先改计划文档再继续，不许悄悄偏离。

### 6.1 动手前（Before coding）

1. **加载上下文**：先读本文件 §0 红线与 §5 Do/Don't；涉及需求读 `PRD-QuantaBot.md`；涉及架构/接口/契约读 `技术选型.md` 对应章节（唯一权威）。
2. **看 git 状态**（注意：QuantaBot 无独立 `.git`，git 命令一律在 `QuantaCommunity` 根仓库执行）：确认当前分支与工作区，识别已有改动；不得覆盖他人/其他任务的未提交修改。
3. **先读再改**：读将要修改的文件及其调用方（上游/下游），理解现状后再决定怎么改，不靠猜。
4. **跟随现有风格**：项目已有模式 X 就用 X，不因个人偏好换新模式。
5. **歧义先问**：需求可做两种以上合理解读、或改动属高风险（删文件/动 demo0/动契约）时，先与用户确认，不自行假设直接执行。

### 6.2 写码中（While coding）

1. **步内红绿节拍（每个可测的小步；用** **`test-driven-development`** **skill）**：新功能/修 bug 按 TDD 走——先写一个会失败的测试并运行，确认它**因预期原因**失败（红，RED）；再写**最小实现**让它通过（绿，GREEN）；重构后全量单测必须仍绿（REFACTOR）。铁律：**没有失败测试就不写生产代码**；每完成一小步跑 `uv run pytest tests/unit -q`，全绿才进下一小步。测试写法遵循 `writing-good-tests` 原则：测真实行为而非 mock 行为，mock 只用于隔离外部依赖（呼应 §4.1 的 ports 假实现）。禁止修改测试断言来让测试通过，禁止 mock 掉审核/幂等来伪装通过（违反诚实口径红线）。可跳过：纯文档、配置措辞、一次性脚本。
2. **最小正确改动**：只改需求要求的部分；不顺手重构、不格式化无关代码、不"顺便修"别处（发现别处问题 → 记入交付摘要，不混入本次改动）。
3. **不编造**：不编造接口字段、数据结构、测试结果、工具能力；拿不准就查代码/文档，或明说"需要确认"。
4. **遵守分层硬规则**：`pipeline/memory → crosscutting → infra`，禁止反向 import；外部客户端只在 `composition.py` 创建后注入。
5. **决策点打点**：每个"回/不回/降级"分支写决策日志（Langfuse trace + SQLite），口径与 PRD F6 一致。
6. **注释与文档同步**：保留仍有效的注释；行为变化时同步修正对应文档（决策变化先改 `技术选型.md`）。

### 6.3 大功能完成后（After coding）

1. **验证三件套（必跑）**：`uv run ruff format src tests` + `uv run ruff check src tests` + `uv run pytest tests/unit -q`。
2. **评测门禁**：改人格/策略/审核/幂等逻辑 → 必须 `uv run pytest tests/eval` 通过 + `promptfoo redteam run --config eval/redteam.yaml` 报告；不达标不得提交发布。
3. **自查 diff**：每行改动都能对应需求；删除自己引入的孤儿代码（未用 import/变量/函数）；diff 里没有与任务无关的变更。
4. **独立审查（用** **`requesting-code-review`** **skill）**：自查通过后，派发 reviewer 子代理按 `code-reviewer.md` 模板审查本次 diff（提供：改动摘要 + 需求出处 + BASE\_SHA/HEAD\_SHA，**不给主会话历史**，保持审查者只看工作产品不被写码过程带偏）。意见按严重度处理：**Critical 立即修、Important 修完才能提交、Minor 记入交付摘要**；对意见先核实再实现，有技术依据可反驳，不表演式附和。高频场景：完成任务后、合并前、卡住要新视角时、复杂 bug 修完后、重构前。纯文档措辞类改动可免。
5. **文档回写**：消灭本次涉及到的 `[待确认]`（或按 Phase 0 结论更新）；决策变化先改 `技术选型.md`（唯一权威）再同步本文件；本文件追加变更记录。
6. **提交**：Conventional Commits（`feat/fix/docs/refactor/test/chore`）；横切保障相关改动（幂等/熔断/kill switch）提交信息必须注明影响面，方便回滚定位。
7. **交付摘要**：说明改了什么、验证证据（命令实际输出，不口报"应该过了"）、审查发现与处理、未完成事项；未经授权不 push。

### 6.4 Git 约定

- **git 执行位置**：QuantaBot 不是独立 git 仓库——所有 git 命令（status/add/commit/branch）在 `QuantaCommunity` 根仓库执行，路径带 `QuantaBot/` 前缀。
- **分支模型**：`main`（可运行基线）+ 功能分支 `feat/<模块>-<一句话>`（如 `feat/trigger-mq-consumer`）、修复分支 `fix/<现象>`；分支生命期 ≤3 天，防长分支漂移。
- **提交频率**：每完成一个可独立验证的小块就提交一次，不攒大 commit。

***

