# AGENTS.md — QuantaBot（AI 评论智能体）

> 本文件是 AI 编码智能体在本仓库的"操作说明书"（AGENTS.md 开放标准）。
> 适用工具：Cursor / Claude Code / Codex / Trae 等读取根目录规则的工具。
> 关联文档：`PRD-QuantaBot.md`（需求与场景）、`技术选型.md`（技术基线，**唯一权威决策来源**，v1.5）。
> 状态：**v0.3 执行版**——架构/红线/技术栈/目录结构/代码规范/工作流已定稿；未标 `[待确认]` 的内容为已确定项，实现时不得臆造冲突；`[待确认]` 项依赖 Phase 0 契约对齐，确认前不要臆造实现。
> 注：原 OpenSpec 决策记录（openspec/）已并入 `技术选型.md`，不再维护；引用它的地方一律以《技术选型.md》为准。

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
src/quanta_bot/  → server.py（FastAPI入口）· consumer.py（MQ主循环）· composition.py（装配根）
  pipeline/      → trigger / decision / context / generation / pipeline（核心链路，按数据流）
  memory/        → ports.py（接口）+ dialogue / user_memory（实现走 infra）
  crosscutting/  → idempotency / breaker / rate_limit / budget / killswitch / moderation（纯逻辑）
  infra/         → settings / main_service / mq / vector / kv / audit_db / tracing（唯一碰外部世界）
prompts/（人格文件，git 版本化）· eval/（评测集 YAML）· tests/{unit,eval}/ · docker/ · docs/
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

- 每个文件头部注释三行：模块名 + 职责 + 边界（"本模块不负责什么"）。
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

## 6. 测试要求（规则要点；阈值细节见 `技术选型.md` §10.2）

- **三层评测**：pytest 规则断言（客观硬伤）+ DeepSeek LLM-as-Judge（主观语气/人格）+ promptfoo redteam（注入/越狱/PII 零成功）。
- **一票否决（P0）**：破防/越界/编造/身份标注缺失/模式错配；情绪陪伴 3 场景 Judge < 4/5；redteam 任一攻击成功。任一失败即门禁不通过。
- **门禁纪律**：改人格/策略必须 `uv run pytest tests/eval` 通过才能发布；跑分时冻结评测集与 prompt；不用 mock 掉审核/幂等来"让测试通过"。

***

## 7. 部署与发布（规则要点；容器细节见 `技术选型.md` §10.3）

- 三容器 compose（app/qdrant/langfuse）一键起；**单实例单进程**，禁止双进程共用本地 SQLite/Qdrant 数据目录。
- `/health` 必须返回各依赖连通性 + kill switch 状态；compose 配 healthcheck + `restart: unless-stopped`。
- 灰度：人格/策略变更走 Redis 配置键白名单 → 观察 → 全量，禁止改完直接全量上线。
- Kill switch：运营写 `quantabot:switch:kill`，Agent ≤5s 暂停消费；回滚 = Redis 配置键改回旧人格版本号。
- 密钥只进 `.env`（不入库）；`.env.example` 入库且字段带注释。

***

## 8. 安全与权限边界

- AI 账号行为边界：不能点赞/关注/私信等权限 `[待确认]`（需主服务侧支持范围）。
- AI 被举报后的处理预案 `[待确认]`。
- 模型/审核/记忆等外部依赖的健康检查 `[待确认]`。
- **隐私**：用户级/长期级记忆只存互动事实（聊过什么/语气偏好），不存敏感个人信息；记忆写入可审计（决策日志）；社区规则层面需用户知情说明 `[待确认]`（落地形式）。
- **Langfuse 访问控制（隐私配套）**：Langfuse trace 含全部用户互动原文，自托管实例必须设账号/基础认证，**不得无认证裸奔在公网**；默认仅开发者可见，运营侧查看决策走主服务侧通道（如需）而非直接开放 Langfuse；Langfuse 数据目录纳入备份与清理策略（trace 按需保留，不无限堆积）。

***

## 9. 工作流约定

- **Git 分支**：`main`（可运行基线）+ 功能分支 `feat/<模块>-<一句话>`（如 `feat/trigger-mq-consumer`）、修复分支 `fix/<现象>`；分支生命期 ≤3 天，防长分支漂移。
- **提交**：Conventional Commits（`feat/fix/docs/refactor/test/chore`）；横切保障相关改动（幂等/熔断/kill switch）提交信息必须注明影响面，方便回滚时定位。
- **提交前必跑**：`uv run ruff format src tests` + `uv run ruff check src tests` + `uv run pytest tests/unit -q`；改人格/策略另跑 `uv run pytest tests/eval`（门禁）。
- **文档更新时机**：
  - 选型/架构决策变化 → 先改 `技术选型.md`（唯一权威），再同步本文件，提交里两者一起出现；
  - 场景/范围/指标变化 → `PRD-QuantaBot.md`；
  - 本文件与《技术选型.md》冲突时，以《技术选型.md》为准并尽快修正本文件。
- **Phase 0 契约落地**：与主服务 Owner 的对齐结论（检索/评论树/写库/审核/MQ 事件/政策内容源/Redis 写权限）拿到后，回填本文件 §3 联调命令与 `infra/settings.py` 环境变量清单。
- **AI 编码智能体特别约定**：改动前先读本文件 §0 红线与 §5 Do/Don't；`[待确认]` 项在确认前不臆造实现（用 TODO + 引用占位，不得编造契约字段）；实现完成回写本文件对应节（消灭 `[待确认]`）并追加变更记录。

***

## 变更记录

| 版本   | 日期         | 说明                                                                                                                                                                                               |
| ---- | ---------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| v0.1 | 2026-09-12 | 框架版：架构 + 红线 + 待确认清单                                                                                                                                                                              |
| v0.2 | 2026-09-13 | 技术基线版：技术栈/链路/Do-Don't 按《技术选型.md》定稿；确认 ruff/Docker 容器化/只读+评论权限/社区条款隐私声明                                                                                                                           |
| v0.3 | 2026-09-13 | 执行版：目录结构细化（装配根/ports/infra/prompts，参考 globex 纪律）；§5 命令落地（ruff/评测门禁/promptfoo）；§6 代码风格定稿（分层纪律/模块规范/Python 规则）；§8 一票否决阈值建议值；§9 Docker 三容器要点+健康检查+单实例约束；§10 Langfuse 访问控制；§11 工作流约定定稿；openspec 引用清理 |
| v0.4 | 2026-09-13 | **瘦身**：移除 §1 概述/§2 技术栈/§3 架构等资料性内容（消除与《技术选型.md》的重复副本）；目录树/测试阈值/部署细节压缩为规则要点+指针，文件级明细迁入《技术选型.md》§10.1-10.3；本文件只保留常驻规则层（红线/命令/代码风格/Do-Don't/工作流）；章节重编号 0-9                                          |
| v0.5 | 2026-09-13 | §1 补充 GitHub 仓库地址（[https://github.com/stephen688/QuantaCommunity](https://github.com/stephen688/QuantaCommunity，QuantaBot)                                                                       |

