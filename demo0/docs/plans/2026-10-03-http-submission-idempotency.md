# HTTP 提交幂等 Implementation Plan

> **For agentic workers:** 按本文件的 3 个主要任务执行，遵循 `demo0/AGENTS.md` 的计划、定向 TDD、事务与验收要求。writing-plans 引用的 superpowers 执行子技能当前未安装，执行时沿用仓库工作流；不因此拆出额外计划或要求用户重复确认。

**Goal:** 普通用户发帖、发回答、发评论及回复时，同一次提交的连点、并发请求和超时重试，在约定保留期内只创建一份业务记录，并返回原成功结果。

**Architecture:** 前端 UUID v4 标识一次提交，Redis 原子占位减少并发重复工作，MySQL 唯一提交凭证作为最终防重依据。提交凭证、业务写入、Outbox 事件与成功响应数据在同一事务中提交，复用既有 Controller → Service → Mapper、Result、鉴权、限流和审核链路。

**Tech Stack:** 现有 Spring Boot / Spring Security / MyBatis / MySQL / Redis / Jackson，以及原生微信小程序 TypeScript、`wx.getRandomValues`、本地存储；不引入 Redisson 或新消息组件。

**Spec:** 本文第 1–5 节就是本次已收敛的需求与设计，任务和验收以本文为准。

**状态：** 代码、定向自动化验收和独立审查已完成（2026-10-03）。正式数据库迁移、发布及微信设备验收未执行，边界见第 10 节与 `docs/api-test/RESULTS.md`。

## 1. 已确定范围与约束

- 覆盖普通用户的 `POST /content/publish`、`POST /answer/publish`、`POST /comment/send`；一级评论、回答下评论和楼层回复都通过第三个入口，不新增回复写接口。
- 点赞、收藏、关注、举报、上传、删除、查询、管理员操作保持现有方案。图片先走现有上传，提交凭证保存最终图片 URL，不覆盖上传防重。
- 前端生成 UUID v4；同一次操作所有重试复用 Token，不能在每次点击或每次 HTTP 请求时重新生成。
- 成功提交凭证至少保留 7 天；小程序从第一次准备发送时起，最多恢复、重试 7 天。本地保存尚未确认的提交内容、上下文和 Token，退出页面或重启后先查询结果。
- 保留现有接口地址和成功响应形状：帖子 `ContentVO`、回答 `AnswerVO`、评论 `Long`，仍包在 `Result` 中。仅为本次契约新增提交请求头、状态查询和可识别的错误，不重做全局错误语义。
- MySQL 为事实源；不通过 Redis 标记推断业务已经成功，不承诺无期限或跨任意新 Token 的防重。
- 保留现有权限、限流、审核、Outbox/Inbox。新增类按相邻代码注入习惯与中文职责注释实现；不重构领域包、不扩大格式化范围。
- 当前工作区已有大量改动，涉及目标 Controller、Service 和文档。实施前逐文件阅读当前 diff，仅叠加本次需求；计划阶段只新增本文件，不提交、部署或执行迁移。
- 用户要求“不要过度测试，不要过于细分 task”：仅 3 个主要任务；核心行为先写失败测试，复用少量公共测试覆盖，不做全仓多轮回归、压测、性能比较或复杂故障演练。

## 2. 当前代码依据

| 位置 | 当前行为与本次接入点 |
| --- | --- |
| `demo0/src/main/java/com/quanta/demo0/content/controller/user/ContentController.java` | `publish` 调用 `ContentCommandService.publish(ContentDTO)`；普通用户 5 次/60 秒 |
| `demo0/src/main/java/com/quanta/demo0/answer/controller/user/AnswerController.java` | `publishAnswer` 调用 `AnswerCommandService.publishAnswer(AnswerDTO)`；普通用户 10 次/60 秒 |
| `demo0/src/main/java/com/quanta/demo0/comment/controller/user/CommentController.java` | `sendComment` 调用 `CommentCommandService.sendComment(CommentAddDTO)`；普通用户 10 次/60 秒，BOT 独立 6 次/60 秒 |
| 三个领域的 `service/impl/*CommandServiceImpl.java` | 发布方法已有 `@Transactional`，本次通过公开 Service 在外层事务内调用，不能 self-invocation 绕开代理 |
| `demo0/src/main/java/com/quanta/demo0/platform/security/aop/RateLimitAspect.java`、`service/impl/RateLimitServiceImpl.java`、`demo0/src/main/resources/lua/rate_limit.lua` | 用户 + 场景 Redis 计数，不识别重复业务；写接口现有 Redis 故障时拒绝策略保留 |
| `demo0/src/main/java/com/quanta/demo0/interaction/service/impl/ContentInteractionServiceImpl.java` | 点赞、收藏采用目标状态及实际变更行数；无需本次 Token 改造 |
| `demo0-miniprogram/miniprogram/services/{content,answer,comment}.service.ts` | `publishContent`、`publishAnswer`、`sendComment` 目前不携带提交凭证 |
| `demo0-miniprogram/miniprogram/pages/{publish,publish-answer,detail-life,answer-detail}/index.ts` | 发布及评论/回复调用处，已有 submitting 标志，但没有完整的提交凭证恢复流程 |
| `demo0-miniprogram/miniprogram/utils/request.ts`、`types/api.ts` | 支持自定义 header；HTTP 非 2xx 默认归类 network，需识别本次 400/409；错误结果目前不暴露独立业务码 |
| `demo0-miniprogram/miniprogram/utils/storage.ts` | 已有用户 ID、本地存储工具；待确认提交必须按真实登录用户隔离 |
| `demo0-miniprogram/project.config.json`、已安装 `miniprogram-api-typings` | 配置基础库 3.7.6；类型声明包含 `wx.getRandomValues({length, success, fail})`，返回 `randomValues: ArrayBuffer` |
| `QuantaBot/src/quanta_bot/infra/main_service.py` | BOT 回帖也走 `/comment/send`，当前不携带提交凭证；需明确兼容边界 |

这里只记录当前代码和类型声明核查，不把历史测试记录当成本次运行证据。

## 3. HTTP 契约与兼容边界

### 3.1 写接口

请求新增 `Idempotency-Key: <UUID v4>`。固定场景为 `content-publish`、`answer-publish`、`comment-send`，由服务器按路由决定，不能让请求声明任意场景。

唯一身份为 `(当前认证 userId, scene, token)`；userId 来自现有认证上下文。后端校验 Token 为标准 UUID v4 并统一小写。Token 不是登录凭证，不记录完整值到日志。

普通用户最终必须携带此请求头；缺失/格式非法返回 HTTP 400、`Result.code=400`。保留短暂部署兼容开关 `quanta.submission-idempotency.require-user-key`，先设 false 发布后端，再发布小程序，确认调用已接入后设 true。false 期间缺失 Token 的旧请求仍走原流程，必须明确标记为未获得本次提交防重保护。

共享 `/comment/send` 的旧 BOT 请求保持兼容：仅服务器已认证的 BOT 角色且系统 Bot 身份成立时，缺失提交头可继续原路径；携带头则使用本次机制。此阶段不改 QuantaBot、不声称解决 BOT 写库超时窗口，普通用户不能靠 body/header 声称 BOT 绕过必填规则。

| 条件 | 行为 |
| --- | --- |
| 首次提交成功 | 原 HTTP 200、`Result.success(原类型 data)` |
| 同一凭证且参数相同，已提交 | HTTP 200，返回首次保存的成功 data，不再执行业务或创建 Outbox |
| 同一凭证但参数不同 | HTTP 409、`Result.code=409`，`msg=提交凭证与内容不一致`，不执行业务 |
| 正在处理、Redis 占位冲突或数据库等待超时，尚未查到结果 | HTTP 409、`Result.code=409`，`msg=提交结果尚未确认，请稍后查询`，`Retry-After: 2`；保持原凭证 |
| 凭证尚在库内但已过期 | HTTP 409、`Result.code=409`，`msg=提交凭证已过期，请先核对发布记录`；不执行业务 |
| 鉴权、授权、限流失败 | 现有 401/403/429 原样保留；相同 Token 的重试仍受限流约束，按 Retry-After 等待 |
| 确认事务已回滚、参数/业务校验失败 | 沿用现有错误；400 后释放待确认凭证，保留当前页面可编辑的原文和回复目标，下次明确提交使用新凭证。退出页面后不额外持久恢复已确认失败的草稿；网络/5xx 不能直接当作已回滚 |

鉴权、Controller 方法授权、现有限流都在调用幂等执行 Service 前完成；不在 Filter 中提前返回结果，不用 AOP 顺序猜测这些边界。业务校验只在首次真正执行时完成；相同内容重放返回历史提交结果，不重新发布已经删除或被审核驳回的记录。

### 3.2 恢复查询

新增 `GET /submission/status?scene=<固定场景>`，仍通过 `Idempotency-Key` 请求头传凭证，避免把完整 Token 放进 URL。查询只允许登录用户读取自己名下凭证；固定场景白名单和 UUID 格式校验与写接口一致。复用 `@PreAuthorize("isAuthenticated()")`，加用户维度独立限流 30 次/60 秒。

返回 `Result<SubmissionStatusVO>`：`status` 为 `SUCCEEDED / UNCONFIRMED / EXPIRED`，成功时返回 `scene`、首次成功 `data` 和 `expiresAt`。`data` 的实际类型按场景映射到上述三种原类型，服务器不按客户端指定类名反序列化。

- `SUCCEEDED`：数据库已提交；前端清理待确认记录、显示成功并刷新对应页面。
- `UNCONFIRMED`：未查到已提交凭证；可能原请求未到达，也可能原事务仍在进行。不能提示“确定失败”，不能换 Token；用户可用原 Token 和原 payload 再试。
- `EXPIRED`：凭证仍存在且已过期，停止重试，提示核对发布记录。
- 不同用户查询同一 UUID，按其自己的凭证空间返回 UNCONFIRMED，不泄漏其他用户是否提交。
- 查询不会执行写操作；页面恢复默认只查询一次，不新增后台长期轮询或自动提交。

### 3.3 7 天边界

服务端 `expires_at = 首次成功事务中的 create_time + 7 天`，前端按本地首次准备发送时间计算恢复期限，不因重试延长。两者允许有很短的时差，前端期限更保守。

保留期内同 Token 有数据库唯一约束保护；日常清理只分批删除已提交且过期凭证。**删除之后，UUID v4 本身没有创建时间，服务器无法区别第一次见到的 UUID 和被清理的旧 UUID。** 因此前端过期后停止自动恢复和重新发送原操作，本方案不承诺恶意换 Token、篡改客户端时间或超过保留期仍能防重。

## 4. 数据与执行设计

### 4.1 MySQL 提交凭证

新增 `demo0/src/main/resources/db/V_http_submission.sql`，InnoDB 表 `tb_http_submission`：

```sql
CREATE TABLE IF NOT EXISTS tb_http_submission (
    id BIGINT NOT NULL AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    scene VARCHAR(32) NOT NULL,
    submission_token CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    request_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    status VARCHAR(16) NOT NULL,
    response_data JSON NULL,
    create_time DATETIME(3) NOT NULL,
    expires_at DATETIME(3) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_user_scene_token (user_id, scene, submission_token),
    KEY idx_status_expiry (status, expires_at)
) ENGINE=InnoDB;
```

状态只需要 `PROCESSING / SUCCEEDED`：PROCESSING 在事务内插入，不单独提前提交；写入原结果并标记 SUCCEEDED 后整体提交。失败回滚不遗留持久的 PROCESSING，恢复读取一般只能看到 SUCCEEDED。Redis 处理中状态和状态查询的 UNCONFIRMED 不等于数据库已持久登记 PROCESSING。

`request_hash` 对 DTO 中参与本次请求的字段作固定字段顺序、固定 null/缺省表示后计算 SHA-256；JSON 属性顺序不影响结果，数组顺序保留。发帖包含类型、标题、正文、图片；回答包含问题 ID、正文；评论包含内容/回答/父楼层/回复目标 ID、正文、图片和 mentionBot。不将 Token、用户 JWT 或原始请求体保存到凭证，不以正文相同代替同 Token。

响应只保存现有成功接口返回的 data，并限制在当前请求/响应大小边界内，不存 HTTP 请求头。重新读取审核后的详情不能替代首次发布结果，以免重放响应改变或记录删除后找不到原 ID。

### 4.2 后端执行边界

公共组件归 `com.quanta.demo0.platform.web.idempotency`，按 `controller/service/service.impl/mapper/entity/dto/vo/properties/exception` 需要创建实际文件，不建立空目录。Controller 只调用公开 Service，不访问 Mapper，公共组件不直接访问领域 Mapper/Entity/ServiceImpl。

公共接口使用明确类型：

```java
public interface SubmissionService {
    <T> T execute(String scene, String token, Object request,
                  Class<T> responseType, Supplier<T> operation);
    SubmissionStatusVO query(String scene, String token);
}
```

`operation` 由三个 Controller 提供，只调用既有领域公开 Service，返回原 VO/Long。操作者由 HTTP 公共 Service 读取已认证身份，不能由 operation 或 body 任意指定。

执行顺序：

1. 在领域修改 DTO 之前计算稳定摘要，读取 MySQL 主库已提交凭证；找到相同请求则返回保存结果，内容冲突或过期则拒绝。
2. 未找到凭证时执行 Redis `SET submission:processing:<userId>:<scene>:<token> <owner> NX EX 30`。owner 是服务器生成的随机值，仅代表本次占位者，不是用户 Token。占位失败再查询一次主库，无结果则返回待确认。
3. 占位成功后，由独立、可被 Spring 代理调用的 `SubmissionTransactionService.execute(...)` 开启 REQUIRED 事务。先普通 INSERT 提交凭证，再调用已有 `@Transactional` 领域 Service，保存响应并更新 SUCCEEDED；整个链路共享 MySQL 事务，禁止凭证单独 REQUIRES_NEW 提交。
4. INSERT 的唯一键冲突在事务代理外捕获；先完成冲突事务回滚，再用新读取查询主库的原结果。不能在已 rollback-only 或旧快照的事务里继续尝试业务。
5. 插入等待、锁等待超时或提交结果不确定时，不删除数据库凭证或用新 Token 重跑。查询主库，已提交则返回原结果，无法确认则返回待确认，保留客户端凭证。
6. Redis 占位清理使用 Lua 比较 owner 后删除；成功提交之后可清理，明确回滚之后可清理，提交不确定时等待 TTL 或查询确认。TTL 失效或 Redis 记录丢失只影响拦截效率，唯一键仍挡住重复写入；不做全局锁、轮询抢锁、续约框架。
7. 幂等组件自己的 Redis 命令失败时记录脱敏降级日志、继续走 MySQL 唯一事务。**这不覆盖现有鉴权和限流对 Redis 的依赖：限流 failClosed 阻断时照常返回 429。**

外层编排 Service 不开启包住全部步骤的大事务；查询主库，避免读取副本延迟。事务结果以数据库提交为准，不能因 afterCommit 的 Redis 清理异常把已成功提交解释为业务失败。清理任务每日分批删过期 SUCCEEDED，单批 500、单轮最多 20 批，复用已有 Spring scheduling 启用机制，不改 Outbox/Inbox 清理策略。

### 4.3 前端提交生命周期

新增 `utils/submission.ts`：按 `(loginUserId, scene, contextKey)` 保存一个待确认提交，字段为 `{token, scene, payload, contextKey, createdAt, ownerUserId}`。contextKey 区分发布页面、问题 ID、评论的内容/回答/回复目标，不能只按正文共用 Token；ownerUserId 供异步恢复和发送前再次校验账户。

- 前端在校验草稿后立即设置 submitting，**在异步实名检查、UUID 生成及本地存储之前**拦住后续点击；finally 恢复按钮，避免 await 前检查、await 后才锁定的间隙。
- 冻结最终 payload；图片使用已上传 URL，同 Token 重试不重新上传并替换 URL。等待确认期间原提交独立保存，不拿当前编辑框覆盖它；当前编辑内容与原提交不一致时先显示冻结原文、停止本次发送并提示明确重试，不能悄悄提交用户看不到的旧正文。评论切换目标属于另一上下文，旧待确认记录继续保存。
- 调用 `wx.getRandomValues` 生成 16 字节，设置 UUID v4 version/variant 位再格式化；生成能力不存在或调用失败则本地提示并保留草稿，不使用 Math.random/时间戳兜底。不新增 UUID npm 依赖。
- 待确认记录必须成功写入本地存储后才允许发请求；不能复用吞掉存储错误的 setStorageSafe 返回值推断已经落盘。记录按账户隔离，账号切换时不恢复其他用户数据，最多保留 20 条未过期待确认记录；容量判断前清理过期恢复数据，满额提示先处理已有提交，不静默淘汰保留期内的未知结果。
- 收到成功响应立即清理记录；网络异常、5xx、结果不确定、处理中保留原记录；401/403/429 也不能推断之前那次已回滚，先处理登录/权限/冷却再查询结果。
- 待确认记录的跨页面持久恢复只针对未知结果；已确认 400 失败释放记录，保留当前页输入和回复目标供修改，不增加一套已失败草稿的持久存储。
- 页面 onShow 或发布页 onLoad 检测本上下文的待确认记录，先查询一次。SUCCEEDED 使用原结果完成已有发布后续动作；UNCONFIRMED 显示可重试的原提交，不自动重发、不生成新 UUID；过期停止重试，提示核对发布记录并清理过期恢复数据。
- GET 恢复响应只处理仍属于当前账户、内存及 storage 都保留同 Token 的记录；POST 响应也重新校验账户。发布成功进入终态，延迟导航期间不再为相同表单生成新 Token。回复恢复从冻结 payload 重建父层和回复目标。
- 新增失败分类 `submissionPending / submissionConflict / submissionExpired` 及 `businessCode?: number`，request.ts 识别本次 HTTP 400/409 与 Result.code；三个 409 文案使用后端约定的固定 msg 映射，不用“全部 409 都是可重试”规则。其他接口非 2xx 分类保持原行为。

## 5. 文件边界

以下相对路径均从仓库根 `QuantaCommunity/` 起算；正式实现保持既有风格，类名围绕本表职责，不照搬一套新框架。

| 范围 | 创建或修改 |
| --- | --- |
| 公共后端 | 新增 `demo0/src/main/java/com/quanta/demo0/platform/web/idempotency/` 下 `SubmissionController`、`SubmissionService`/实现、`SubmissionTransactionService`、`SubmissionMapper`、`HttpSubmission`、`SubmissionStatusVO`、`SubmissionProperties`、`SubmissionException` 和集中请求摘要工具；属性与调度注册采用相邻现有方式 |
| 数据与 Redis | 新增 `demo0/src/main/resources/db/V_http_submission.sql`、`mapper/platform/HttpSubmissionMapper.xml`、`lua/submission_release.lua`；`application.yml` 添加本次配置，`.env.example` 仅添加兼容开关的非敏感示例 |
| 入口与错误 | 修改上述三个用户 Controller 和 `demo0/src/main/java/com/quanta/demo0/platform/web/handler/GlobalExceptionHandler.java`；如 Spring Security URL 默认规则不能保障新增查询入口，再最小修改 `platform/security/config/SecurityConfiguration.java` |
| 小程序公共能力 | 新增 `demo0-miniprogram/miniprogram/utils/submission.ts`、`services/submission.service.ts`；修改 `utils/request.ts`、`types/api.ts` 和三个发布/评论 service |
| 小程序页面 | 修改 `pages/publish/index.ts`、`pages/publish-answer/index.ts`、`pages/detail-life/index.ts`、`pages/answer-detail/index.ts`；只在需要恢复提示/原提交重试入口时最小修改对应 WXML。专业帖下回答评论已在 answer-detail，不给 detail-pro 凭空新增评论功能 |
| 测试 | 新增后端 `platform/web/idempotency/SubmissionServiceTests.java`、`SubmissionHttpTests.java`、`reliability/SubmissionIdempotencyIntegrationTests.java`；对应 `src/test/resources/db/submission-runtime-schema.sql` 沿用既有 reliability fixture 的结构和与迁移等价的凭证表，使用隔离数据库；小程序新增 `tests/submission.test.cjs`、`submission-page.test.cjs`、共享 `production-compiler.cjs`，复用 TypeScript 转换和现有 wx stub 模式 |
| 文档与验收 | 本计划持续勾进度；新增 `demo0/docs/api-test/cases/08-submission-idempotency.http`，真实结果只追加到已有 `docs/api-test/RESULTS.md`，修改既有 02/04 写用例以携带 Token |

普通用户必填头改变已有契约：同步更新 `VerifiedUserMethodSecurityTests` 等受影响 HTTP fixture，以及 bot-comment.test.cjs 的 sendComment 调用参数；不删测试、不放宽业务断言。小程序已有 TS 与历史生成 JS 共存，Node 测试用当前 TS 临时编译到系统临时目录后加载，不批量生成/覆盖仓库中的 JS；微信开发者工具使用现有 TypeScript 编译配置。

## 6. Task 1：公共幂等能力和三个 HTTP 入口

**交付：** MySQL 唯一凭证、Redis 占位、原结果复用与状态查询可用；保持既有发布/审核/Outbox 业务链。

- [x] 公共行为、状态契约、存量方法授权与架构定向检查 13 项通过；真实接口另覆盖三路由、缺头 400、内容冲突 409、匿名状态 401、用户隔离，BOT 缺头双条件补跑 1 项通过。未为保留的限流链路另建故障矩阵。
- [x] 落地第 3–4 节契约、迁移和公共组件，三个 Controller 只包装现有 Service 调用。例：`Result.success(submissionService.execute("content-publish", key, dto, ContentVO.class, () -> contentCommandService.publish(dto)))`。凭证、业务和 Outbox 共用事务。
- [x] 新增 5 个真实 HTTP/MySQL/Redis 集成用例，通过同 Token 重放、2 请求并发、写入后的事务回滚及 Redis 占位丢失后的重放。清理只删已完成过期凭证和 Lua owner 匹配经代码审查，未追加专项故障演练。
- [x] Mapper、代理事务与包边界已验证。普通用户强制头开关保持默认 false；只在隔离集成配置 true，不执行开发/生产迁移。

接口示例（发布 body 仍按当前 ContentDTO）：

```http
POST {{BASE_URL}}/content/publish
authorization: {{USER_TOKEN}}
Idempotency-Key: 36b8f84d-df4e-4d49-b662-bcde71a8764f
Content-Type: application/json

{"contentType":2,"title":"提交幂等验收","content":"测试正文","images":[]}
```

## 7. Task 2：小程序 Token、恢复与提交接线

**交付：** 发帖、回答、评论和回复保留同一次操作凭证；超时退出后能够先查询再重试，不重新创建业务。

- [x] 当前生产 TS 的 wx stub 行为覆盖 Token 复用、冻结 payload、成功换号、账户/上下文隔离、存储失败、状态查询、过期停止、容量清理及页面恢复竞态。新增行为检查不使用源码字符串匹配。
- [x] 在 submission.ts 实现 UUID v4 和待确认记录生命周期，接入现有登录用户 ID；锁定发生在异步准备之前。两个字节设置为 `bytes[6] = (bytes[6] & 0x0f) | 0x40`、`bytes[8] = (bytes[8] & 0x3f) | 0x80`，再转标准 8-4-4-4-12 十六进制字符串。
- [x] 三个写 service 显式传 submissionToken；四页接入冻结原提交、成功清理、一次查询和原提交重试。发送前/响应后检查账户、发送前检查过期，回复恢复保持原父层和目标。
- [x] request.ts 仅识别本次路由的 400/409，存量 bot-comment fixture 改为临时编译当前 TS。相关 20 项通过，最后回复恢复补丁只补跑页面 3 项通过，累计 21 项；typecheck 通过。设备验证单列为发布前门禁，不重设计页面或新增后台轮询。

## 8. Task 3：少量真实验收、兼容切换与文档收口

**交付：** 以业务记录、Outbox 和原返回 ID 证明防重，同时验证小程序恢复；验收记录如实区分 PASS、FAIL、BLOCKED。

- [x] 使用隔离 Testcontainers MySQL/Redis/RabbitMQ 与随机 HTTP 端口，三路由及楼层回复同 Token 重试返回原 ID，第二次不新增业务或 Outbox；fixture 凭证结构与迁移等价，开发库未改。
- [x] 2 请求并发、状态恢复、删除 Redis 占位后重试均通过；普通评论在既有关闭审核 APPROVED 策略下的最终状态和公开列表均只出现一次。对应通知仅验收到 Outbox，实际消费者落库未执行，记录 SKIP。
- [ ] 小程序只做两个设备/开发者工具场景：发送中连点；发送结果未知后退出再返回先查询。覆盖其中一个评论回复上下文，并确认账号切换不恢复他人内容。BOT 共享入口只做一条既有真链路兼容抽样，不重跑全部人格/LLM 场景。
- [x] 更新 `08-submission-idempotency.http`、受影响写用例、RESULTS.md 和本计划。发布顺序记录为迁移 → 后端兼容模式 → 小程序 → 普通用户必填开关；正式发布及开关切换尚未执行。

### 最小验证集合

测试按公共能力集中，路由与身份差异参数化，不给每个 Controller 复制整套故障测试。

| 集合 | 必须观察的结果 |
| --- | --- |
| HTTP 契约 | 三入口返回类型不变；普通用户缺头/非法头 400；相同凭证异内容 409；处理中 409；原 401/403/429；状态只读自己的结果；BOT 兼容 |
| 唯一性与事务 | 同 Token 重复/并发只有一份业务；Outbox 增量只等于首次提交的增量，不能假定首次只产生一个事件；强制业务异常时凭证、业务、Outbox 一起回滚 |
| 故障兜底 | Redis 占位丢失/TTL 失效不重复；幂等 Redis 故障可进入 DB，但现有限流故障仍按原策略阻断；提交结果未知通过主库恢复，不用删凭证重跑 |
| 凭证边界 | 固定摘要忽略 JSON 属性顺序；业务字段变化拒绝；不同用户/场景隔离；7 天清理仅删已完成过期记录；旧 owner 不得删新占位 |
| 小程序 | UUID version/variant；连点与重试复用；成功后换号；本地存储失败不发送；恢复先查询；过期停止；账户隔离 |
| 真实链路 | HTTP → 唯一业务/Outbox → 评论审核最终状态 → 列表/通知；一条 BOT 兼容抽样。依赖不齐如实 BLOCKED，不以 HTTP 200 代替验收 |

执行时从 `demo0/` 运行：

```powershell
docker info
mvn '-Dtest=SubmissionServiceTests,SubmissionHttpTests,SubmissionIdempotencyIntegrationTests,VerifiedUserMethodSecurityTests,PackageArchitectureTest' test
```

从 `demo0-miniprogram/` 运行：

```powershell
npm run typecheck
node --test tests/submission.test.cjs tests/submission-page.test.cjs tests/bot-comment.test.cjs
```

新的 Node 测试把所需生产 TS 编译到系统临时目录，并让相关旧测试使用同一份产物，确保测到新 TS，不扩大仓库 JS diff。Maven 新增测试类完成后执行上述命令；计划阶段不运行不存在的测试。目标检查通过后，只因新的改动、失败或未解决疑点补跑，不反复扩大到全量，也不新增压测。本次正式实现完成后，依 `AGENTS.md` 对事务/幂等新增测试做一次关键断言的变异抽查并安排独立只读审查，不拆成额外实现任务。

## 9. 上线和回滚边界

- 迁移新增独立表和索引，不修改既有帖子、评论、回答表及历史业务记录。生产应用前先核对表/索引与环境，测试数据只清理本轮创建的记录。
- 回滚前先关闭普通用户必填开关，避免旧小程序被缺头拒绝；回滚应用后暂保留提交凭证表和数据，不在回滚时直接 DROP。重新上线必须保持已记录凭证的语义。
- 关闭或回滚本次功能意味着入口提交防重不再保证；不能把兼容放行标成完整防重已启用。
- 保留期限内原结果只代表“首次提交成功”，不代表内容审核通过或仍然可见；前端后续展示仍通过现有详情/列表接口。
- 性能成本记录为少量 Redis/DB 读写及凭证存储；本次不承诺延迟提升，不引入长期分布式锁或凭证管理后台。后续真有压力再单独测量。

## 10. 本次实际交付与待发布门禁

- 已交付后端公共能力、三个入口、小程序四页及状态恢复、SQL/Lua、HTTP 用例和少量自动化验收。基础后端 13 项、BOT Service 双条件 1 项、隔离真实集成 5 项分别通过；小程序累计 21 项相关检查及 typecheck 通过，没有全仓回归或压测。
- 独立只读审查发现的恢复竞态、发送后账号切换、过期容量、回复上下文及非幂等错误分类已修复；审查者复查后无未解决的 P1/P2。400 后只保留当前页可编辑草稿的边界已明确。Token 复用断言的临时副本变异按预期失败，原测试未被覆盖。
- 未执行微信开发者工具/真机验收、通知实际消费、真 BOT HTTP/LLM 链路、生产迁移、部署及普通用户必填开关切换。上表未勾项和 RESULTS.md 的 PENDING/SKIP 就是这些边界，不能将代码完成表述为已上线。
