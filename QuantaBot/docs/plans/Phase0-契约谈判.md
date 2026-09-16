# Phase 0 契约谈判计划 — QuantaBot × demo0

> **定位**：Phase 0 外部契约对齐轨道的实施计划——记录 grill-me 需求结论、demo0 现状侦察证据、七项契约草案文本与 demo0 侧改造清单。
> **上游权威**：红线与工作流读 `AGENTS.md`；里程碑结构读 `总计划.md`；本文件结论敲定后，契约细节唯一权威落 `docs/技术选型.md`，本文件只留谈判过程与状态。
> **谈判方式**：双方均为本人（QuantaBot Owner = demo0 Owner），"谈判"= 逐项对照 demo0 代码核实 → 出契约文本 → 授权后实施 demo0 改造 → 勾掉 `总计划.md` §1 对应项。
> **状态**：`谈判中`（七项结论已对齐，待逐项落契约文本并实施）

---

## 1. 需求结论（grill-me 对齐，2026-09-15）

按链路（身份 → 触发 → 上下文 → 生成 → 发布 → 控制）逐项拷问，结论如下：

| # | 问题 | 结论 | 影响 |
|---|---|---|---|
| G1 | bot 以什么身份存在？ | **demo0 新增系统账号**（非微信养号、非内网裸奔）。bot 拥有独立 user_id + 专属角色，昵称/头像带 AI 标识 | P0-5 硬前置；所有接口鉴权围绕它设计 |
| G2 | 服务间鉴权机制？ | **系统账号 + 长期 service token**。demo0 签发长期 token，复用现有 JWT 过滤器链认证，BaseContext 映射到 bot user_id；token 可吊销 | P0-2/P0-3(内容源)/P0-5 全部走这套鉴权 |
| G3 | 事件触发时机？ | **评论审核通过后发**。bot 只见合规内容，不会回复后续被驳回的评论；代价是回复延迟多一个机审时长（可接受） | P0-1 事件挂在现有"审核通过"钩子处 |
| G4 | @ 检测归属？ | **前端 @ 卡片方案**：选中机器人 → 插入结构化 @；直接回复机器人也自动带 @。demo0 在评论创建时已知道是否命中 bot，**事件里直接打结构化 mention 标记**，bot 不做文本正则猜测 | P0-1 事件字段含 `mentionedBot: boolean`；前端需加 @ 选择卡片 |
| G5 | bot 回复怎么过审核？ | **双层**：bot 侧规则预检（已有，同步）+ demo0 对 bot 评论开启阿里云 AI 机审（异步，复用现有链路）。demo0 需让 `targets.comment.enabled` 对 bot 生效（当前全局为 false） | 红线 §0.3/0.4 第一天存在；P0-4 无需新接口 |
| G6 | P0-2 两个缺口怎么补？ | **demo0 新增两个只读接口**：①按 comment_id 查评论+父级链；②按 post_id+user_id 查历史发言（分页）。bot 一次调用拿全，楼层快照防穿越依赖接口② | P0-2 补口子，demo0 侧只读、成本低 |
| G7 | RAG 归属与内容源？ | **RAG 归 QuantaBot 侧自建**（Qdrant 已在 M2 compose 规划内），demo0 的 `/rag/search` 留给社区搜索继续演进。P0-3 契约改形为**内容源同步**：①政策文档导入通道；②社区帖子/回答增量同步通道 | 与"RAG 链路规划写在 Agent 项目侧"的既定决策一致；bot 生成素材自主可控 |

**明确不做**（本次谈判范围外）：bot 主动触发链路（Phase 2）、demo0 换正式向量库（独立演进）、跨服务分布式事务。

---

## 2. demo0 现状侦察摘要（证据已核实，2026-09-15）

| 契约 | 现状 | 关键证据 |
|---|---|---|
| P0-1 | Outbox+RabbitMQ 基建成熟（至少一次+Inbox 幂等）；**无评论事件**：现有通知消息无评论全文、无 @ 标记（@ 功能不存在）；新增事件类型只需扩 `OutboxEventType` + `OutboxRouteRegistry.resolve()` 加分支 | `mq/outbox/OutboxDispatcher.java`、`OutboxRouteRegistry.java`、`mq/message/NotificationEventMessage.java`、`RabbitMQConfig.java`（交换机/队列/retry/dlx 命名模式 `<rk>.exchange/.queue/.retry.queue/.dlx.queue`） |
| P0-2 | 帖子主楼 `GET /content/detail/{contentId}`、评论分页 `GET /comment/list`、`GET /comment/replyList` 均有（JWT）；**无父级链接口、无按 user_id 查历史发言接口**；评论数据模型有 `parentId/replyCommentId`（父链可推导） | `controller/user/ContentController.java:61`、`CommentController`、`dto/CommentPageDTO.java` |
| P0-3 | `POST /rag/search`（JWT，双路检索+AI 总结，contentType 参数）；向量库为本地 JSON 文件，docKind 仅 POST/ANSWER | `controller/user/RagController.java:45`、`rag/vector/VectorStorePersistenceService.java` |
| P0-4 | 阿里云 AI 机审内部链路完整（文本+图片、指纹防重、留痕 `tb_moderation_record`），**异步**；**无对外审核 REST**；`quanta.moderation.targets.comment.enabled: false`（评论不进 AI 机审） | `modertion/client/AliyunTextModerationClient.java`、application.yml:148-169 |
| P0-5 | `POST /comment/send`（JWT + `ROLE_VERIFIED_USER` + 限流 10 次/60s；字段 `contentId/answerId/parentId/replyCommentId/replyUserId/content≤500字/imageUrls≤5`；userId 取 BaseContext）；**无机器人账号机制**，用户全来自微信登录 | `controller/user/CommentController.java:39`、`dto/CommentAddDTO.java`、`security/OptionalJwtAuthenticationFilter.java`（过滤器→`TokenAuthenticationService.authenticate`→BaseContext） |
| P0-6 | 向量库无任何政策文档，docKind 枚举无 POLICY，同步链路仅 POST/ANSWER 两类 | `rag/vector/RagDocumentConverter.java:165,181`、`data/vector-store/content-vector-store.json` |
| P0-7 | Redis 在用（登录态/限流/推荐流/去重），键风格小写冒号 `域:子域:[id]`，常量集中 `RedisConstants`；无分布式锁 | `constant/RedisConstants.java`、`lua/rate_limit.lua` |

**全局发现**：demo0 全库对 QuantaBot/bot 零感知（grep 零命中），所有 bot 专属物均需新建；但 Outbox 与 RAG 两大基建现成，改造成本可控。

---

## 3. 七项契约草案（谈判目标文本，逐项敲定后落 `docs/技术选型.md`）

### C-1 评论事件契约（P0-1）

- **通道**：RabbitMQ 新增 `quantabot.exchange` / `quantabot.comment.queue` / rk `quantabot.comment.created`（+retry/dlx，命名沿用 demo0 现有模式）。
- **触发时机**：评论审核通过（auditStatus → PASS）后由 Outbox 发出；被驳回/转人工的评论不产生事件。
- **触发条件**：`mentionedBot = true`（见 C-4）——只推 @ 命中 bot 的评论，避免 bot 消费全量评论。
- **消息体**（新增 `BotMentionMessage`）：
  - `eventId`（Outbox 唯一 ID，消费幂等键）、`eventType`、`occurredAt`、`retryCount`
  - `commentId`、`postId`（= contentId）、`answerId`（专业区可空）
  - `commenterUserId`、`commentContent`（**全文**，≤500 字）、`commentImages`（可空）
  - `mentionedBot: boolean`、`botTriggerKind`：`mentioned`（卡片 @）/ `replied`（直接回复 bot 评论）
  - `parentId`、`replyCommentId`（供 bot 快速定位楼内位置，父链仍走 C-2 接口）
- **投递语义**：至少一次 + bot 侧以 `eventId`/`commentId` 幂等（与现有 Inbox 模式同构）。
- **实现落点**：`OutboxEventType` 增 `BOT_MENTION_REQUESTED`；`OutboxRouteRegistry.resolve()` 增分支；`CommentAuditServiceImpl` 审核通过钩子处（现有 `createCommentNotificationEvents` 同位置）按 @ 命中写 Outbox。

### C-2 上下文读取契约（P0-2，demo0 新增只读接口）

- **① 评论+父级链**：`GET /bot/comment/chain?commentId={id}` → 返回该评论本体 + 逐级父评论（追溯至一级评论）+ 帖子主楼摘要。响应为 `commentId/parentId/replyCommentId/userId/content/images/createTime` 有序列表。
- **② 帖子评论树分页**：`GET /bot/comment/tree?postId={id}&pageNum&pageSize&sortType` → 语义同现有 `/comment/list`，但强类型 schema（现有 `CommentPageVO.list` 是 `List<Map>` 占位，需补 DTO）。
- **③ bot 历史发言**：`GET /bot/comment/history?userId={botUserId}&postId={id}&pageNum&pageSize`（postId 可空 = 全站历史，M3 用户级记忆用）。
- **鉴权**：bot service token（见 C-5），三接口均为只读，不改动任何业务数据。
- **防穿越支撑**：接口 ③ 是 M2 验收"楼层快照"的数据来源。

### C-3 内容源同步契约（P0-3 改形 + P0-6 合并处理）

- **归属变更**：RAG 检索归 QuantaBot 自建（Qdrant，M2 compose 已规划）；demo0 `/rag/search` 不对 bot 承诺，继续服务社区搜索。
- **① 政策文档通道（P0-6）**：demo0 提供 `POST /bot/knowledge/policy-docs`（或约定文件目录），bot 拉取后自行 chunk、embedding、入 Qdrant（`docKind=POLICY`）。文档来源与更新频率由 Owner 维护。
- **② 社区内容增量同步**：`GET /bot/content/sync?since={ts}&pageNum&pageSize`，返回审核通过（可见状态）的帖子/回答（含 `contentId/answerId/docKind/content/createTime/updateTime`）；bot 定时拉取增量，全量初始化走 `since=0`。帖子删除/编辑的清理语义见 §5 待定项。
- **鉴权**：bot service token。

### C-4 @ 卡片与 mention 标记契约（前端 + demo0）

- **前端**：评论框 @ 按钮 → 弹出机器人选择卡片（bot 头像/昵称/AI 标识）→ 选中插入结构化 @（前端记录 `mentionBot=true`，评论内容含 @ 昵称文本）；直接回复 bot 的评论（`replyUserId == botUserId`）自动视为命中。
- **demo0**：评论创建/审核链路计算 `mentionedBot = (前端标记) || (replyUserId == botUserId)`，随 C-1 事件发出。
- **兜底**：若前端标记缺失，bot 侧对文本 `@<bot昵称>`（人格定名后同步）做一层容错正则——仅作降级，不作为主判定。

### C-5 系统账号与服务间鉴权契约（P0-5 前置）

- **系统账号**：demo0 建 bot 用户（固定 user_id，昵称=量量/波仔/路路 定名后同步，头像带 AI 标识，角色新增 `ROLE_BOT`），不走微信登录。
- **鉴权机制**：demo0 为 bot 签发**长期 service token**（JWT，`TokenAuthenticationService` 识别后 `BaseContext = botUserId`，角色 `ROLE_BOT` → 满足 `/comment/send` 的 `@PreAuthorize("hasRole('VERIFIED_USER')")`，或为 bot 单独放行配置）；token 可吊销，泄露即换。
- **写库**：bot 发评论走现有 `POST /comment/send`，请求体即 `CommentAddDTO`，不改字段；限流单独设档（scene=`comment-send-bot`，保守值建议 ≤6 条/分钟，待定项回填）。
- **发帖身份标识**：bot 评论落库带 AI 标识（前端渲染"AI 生成"角标的依据），实现方式（独立字段 or 用户类型）实施时定。

### C-6 审核契约（P0-4）

- **双层**：bot 侧规则预检（已实现，同步，M1 交付物）+ demo0 AI 机审（异步）。
- **demo0 改造**：让 `quanta.moderation.targets.comment.enabled` 对 bot 来源的评论生效（按 `userId == botUserId` 判定，不影响普通用户现状），复用现有异步审核链路与 `tb_moderation_record` 留痕。
- **失败语义**：机审驳回 → bot 回复不可见（现有链路自动处理）；bot 侧超时/异常 → 静默不回（红线 §0.3）。
- **不做独立同步审核接口**——bot 所有写操作都过 `/comment/send` 单一入口，审计路径唯一。

### C-7 控制面键命名契约（P0-7）

- **键命名**（沿用 demo0 `域:子域:id` 风格，`RedisConstants` 新增段集中定义）：
  - `quantabot:switch:kill`（kill switch，true=暂停消费）
  - `quantabot:switch:graylist`（灰度白名单，SET of userId）
  - `quantabot:switch:persona_version`（人格版本，string）
  - `quantabot:cost:{yyyyMMdd}`（日累计成本，bot 读写）
- **写入口**：运营侧（Owner 手动或后续 admin 面板）直接写 Redis；bot 轮询 ≤5s。
- **demo0 不实现 bot 控制面逻辑**——键归 bot 所有，demo0 仅约定命名空间不冲突。

---

## 4. demo0 侧改造清单（实施顺序，均需 Owner 授权后进行）

| # | 改造项 | 服务契约 | 位置/落点 | 规模估计 |
|---|---|---|---|---|
| D1 | 系统账号 + service token 鉴权 | C-5 | 用户表 seed + `TokenAuthenticationService`/`SecurityConfiguration` 增 bot 识别分支 | 中 |
| D2 | bot 评论走 AI 机审 | C-6 | application.yml + 审核链路按 bot userId 判定 | 小 |
| D3 | @ 卡片前端 + mention 标记 | C-4 | 小程序/前端评论框 + demo0 评论创建链路 | 中 |
| D4 | bot 评论事件（Outbox 新类型 + 路由 + MQ 拓扑） | C-1 | `OutboxEventType`、`OutboxRouteRegistry`、`RabbitMQConfig`、`CommentAuditServiceImpl` 钩子 | 中 |
| D5 | 只读接口三件（父链/树分页/历史发言） | C-2 | 新 `controller/bot/` 命名空间 + 强类型 VO | 中 |
| D6 | 内容源同步接口 + 政策文档通道 | C-3 | 新 `controller/bot/` + 复用现有内容查询服务 | 小-中 |
| D7 | Redis 控制面键命名 | C-7 | `RedisConstants` 增段（仅命名约定） | 极小 |

**依赖序**：D1 → D2/D4 → D5/D6（D3 前端可与 D4 并行）。D1 是全部接口的前置。
**改造完成后**：逐项勾掉 `总计划.md` §1 P0-1~P0-7，契约细节回填 `docs/技术选型.md`，M2 解阻。

---

## 5. 待定项（实施时回填）

- [ ] bot 昵称定名（量量/波仔/路路）→ C-4 @ 文本、C-5 昵称头像
- [ ] service token 有效期与吊销机制细节 → C-5
- [ ] bot 限流配额具体值 → C-5
- [ ] 内容同步的删除/编辑清理语义 → C-3
- [ ] AI 标识的存储实现（独立字段 or 用户类型）→ C-5
