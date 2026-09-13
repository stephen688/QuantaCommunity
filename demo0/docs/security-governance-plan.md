# 安全治理优化详细实施计划

## 一、文档目的

本计划用于在不推翻现有微信登录、JWT、Redis 登录态和业务代码结构的前提下，完成项目的统一认证、细粒度授权、WebSocket 鉴权复用、管理员操作审计、接口限流和管理端权限展示。

本次改造不是“加一个 Spring Security 依赖”就结束，而是要解决以下真实问题：

1. HTTP 鉴权、认证校友判断和 WebSocket 鉴权分散在不同拦截器中，存在重复逻辑。
2. `@RequireAuth` 对应的 `AuthInterceptor` 当前没有注册，注解虽然存在，但认证校友限制可能没有生效。
3. 推荐流和搜索接口属于“可选鉴权”：匿名可以访问，携带有效 Token 时又需要返回点赞态或个性化结果，不能简单配置成纯匿名或强制登录。
4. 当前管理员权限主要依赖 `is_admin`，只能回答“是不是管理员”，不能区分内容审核、用户封禁、事件重放和审计查看等高风险能力。
5. 管理员降权、封禁、退出登录和 Token 失效之间的语义没有形成统一规则。
6. `BaseContext.getCurrentId()` 在现有代码中有约 59 处调用，不能为了引入 Spring Security 一次性全部替换。
7. WebSocket 的 STOMP `CONNECT` 不经过普通 Servlet Filter，不能把 HTTP 方案生硬复制过去。
8. 401、403、429、CORS、公开路径和管理端前端行为还没有形成统一契约。
9. 内容审核、删除、封禁、事件重放等高风险操作缺少独立、可查询、不可被普通业务回滚吞掉的审计记录。

最终目标是形成一条完整链路：

```text
请求进入
  -> 识别公开、可选鉴权或强制鉴权接口
  -> 校验 JWT 签名和有效期
  -> 校验 Redis 中当前有效 Token
  -> 加载账号状态、角色和权限
  -> 写入 SecurityContext 与 BaseContext
  -> 执行 URL 级和方法级权限校验
  -> 对高风险操作限流
  -> 执行业务
  -> 记录成功或失败审计
  -> 返回统一 Result 结构
```

---

## 二、现状基线与必须保留的行为

### 1. 当前登录态模型

当前登录成功后：

- JWT 中保存 `userId` 和 `isAdmin`。
- Redis 使用 `login:token:{userId}` 保存当前有效 Token。
- Redis 登录态有效期为 7 天。
- 同一个用户再次登录会覆盖旧 Token，因此现状实际上是单有效会话模型。
- 每次受保护请求都需要校验 JWT，并将请求 Token 与 Redis 中的 Token 进行比较。
- 封禁用户时会删除 Redis 登录态并写入封禁标记。

因此，本项目不是纯无状态 JWT，而是“JWT 签名凭证 + Redis 有状态会话控制”的混合方案。本次优化保留这个方案，不把它包装成完全无状态认证。

当前管理端和小程序端共用 `login:token:{userId}`。同一账号在两个客户端登录会互相覆盖，这是现有“全端单会话”语义，不属于 Spring Security 迁移造成的回归。第一阶段明确保留并加入测试，避免无意改变；如果后续需要管理端和小程序同时在线，再改为带 `clientType` 的会话 Key和 JWT audience，并确保封禁、强制下线、角色降权可以一次撤销该用户全部客户端会话。

### 2. 必须保留的可选鉴权语义

当前以下接口允许匿名访问，但有效登录用户访问时需要读取用户身份：

- `GET /content/recommend`
- `/search/content` 下的搜索请求
- `GET /search/trending`

第一阶段保持当前行为：

| 请求情况 | 目标行为 |
| --- | --- |
| 不携带 Token | 匿名访问成功，当前用户为空 |
| 携带有效 Token | 登录态访问成功，能返回点赞态或个性化数据 |
| 携带过期、伪造或已失效 Token | 暂时保持现状，降级为匿名；通过测试锁定该行为 |

说明：无效 Token 是否应该在可选接口上返回 401，是产品和安全策略选择。第一阶段不改变现有行为，待迁移稳定后再单独评估，避免推荐流和搜索功能发生隐性回归。

### 3. 必须保留的业务上下文

现有 Controller 和 Service 广泛依赖 `BaseContext.getCurrentId()`。第一阶段不全量改成 `SecurityContextHolder`，而是在统一认证 Filter 中同时写入：

- `SecurityContext`：交给 Spring Security 完成授权。
- `BaseContext`：兼容现有业务代码。

请求完成后必须同时清理两个上下文。异步线程、定时任务和 MQ 消费者不得假设能够继承 HTTP 请求的 ThreadLocal。

### 4. 必须保留的 WebSocket 行为

WebSocket 继续通过 STOMP `ChannelInterceptor` 在 `CONNECT` 阶段校验 Token，并绑定 `Principal`。本次只抽取公共认证能力，不把 STOMP 消息强行塞进 HTTP Filter Chain。

### 5. 现有接口响应兼容

管理端已经同时处理 HTTP 状态码和 `Result.code`。安全改造后统一约定：

- 认证失败：HTTP 401，响应体 `Result.error(401, message)`。
- 已登录但权限不足：HTTP 403，响应体 `Result.error(403, message)`。
- 触发限流：HTTP 429，响应体 `Result.error(429, message)`。
- 业务参数错误：HTTP 400。
- 不再用 HTTP 401 包装所有鉴权、权限和封禁异常。

---

## 三、明确不在本阶段做的内容

为控制范围，本阶段不做以下改造：

1. 不更换微信登录流程。
2. 不接入 OAuth2 授权服务器或第三方统一身份平台。
3. 不引入微服务网关。
4. 不为了安全治理更换 RabbitMQ、Redis 或数据库。
5. 不一次性替换约 59 处 `BaseContext` 调用。
6. 不把管理端按钮隐藏当成真正授权，最终权限始终以后端为准。
7. 不立刻建立高度动态的“角色、权限、菜单、接口”四套配置表。
8. 不在本任务中同时引入 Flyway；数据库脚本先延续项目当前 `src/main/resources/db` 的组织方式。
9. 不重写现有 WebSocket 推送链路。
10. 不在没有压测数据的情况下引入复杂分布式限流组件。

---

## 四、核心安全语义

### 1. 身份、角色和权限分开

三个概念必须在代码和面试表述中分开：

- 身份：当前请求是谁，核心字段是 `userId`。
- 角色：用户属于哪一类，例如普通用户、认证校友、内容审核员、超级管理员。
- 权限：允许执行哪项具体操作，例如 `CONTENT_AUDIT`、`USER_BAN`、`EVENT_REPLAY`。

角色用于聚合权限，业务方法最终校验具体权限。不要在代码里到处写 `isAdmin == 1`。

### 2. 401、403 和 429 的边界

| 场景 | 状态码 | 含义 |
| --- | --- | --- |
| 没有 Token，访问强制登录接口 | 401 | 尚未认证 |
| JWT 伪造、过期或 Redis Token 不匹配 | 401 | 当前凭证无效 |
| 用户被封禁并强制下线 | 401 | 当前会话被撤销 |
| 已登录，但不是认证校友 | 403 | 身份有效但不满足业务资格 |
| 已登录，但缺少管理权限 | 403 | 身份有效但权限不足 |
| 超过登录、上传、发布或重放频率 | 429 | 请求过于频繁 |

### 3. 降权与强制下线的区别

- 权限降级：用户仍然是有效登录用户，但不再拥有某个权限。访问对应接口应该返回 403。
- 强制下线：删除 Redis 当前有效 Token，旧 Token访问所有强制鉴权接口返回 401。

本计划采用以下规则：

1. 修改管理员角色或权限时，角色数据以数据库为准，并清理权限缓存。
2. 对高风险降权操作，默认同时删除 Redis 登录态，要求管理员重新登录。
3. 测试中分别覆盖“有效会话但无权限返回 403”和“角色变更导致会话撤销返回 401”，不再把两者混为一谈。

### 4. JWT 中只保留稳定声明

迁移后 JWT 至少保留：

- `userId`
- 签发时间
- 过期时间
- 可选的 Token 标识 `jti`

不再把 JWT 内的 `isAdmin` 当成授权事实源。历史 Token 即使带有 `isAdmin`，也只能兼容解析，不能直接转换为管理权限。

### 5. Redis 登录态的定位

Redis 负责：

- 保存当前唯一有效 Token。
- 支持退出登录和新登录顶掉旧会话。
- 支持封禁、强制下线和高风险降权后的即时失效。
- 为后续会话列表和风险控制保留扩展空间。

Redis 不负责永久保存角色和权限。管理员角色和权限第一版以数据库为事实源，高风险角色变更同时撤销登录态。认证校友状态属于低频变更、高频读取数据，第一版允许使用短 TTL 缓存，但审核通过、驳回或撤销时必须主动失效；Redis miss 始终回源数据库。

### 6. HTTP 用户权限与内部系统调用严格分开

`SecurityContext` 表示一次外部请求携带的用户身份。MQ 消费者、定时任务和 Outbox Dispatcher 运行在内部线程中，没有 HTTP 请求，也不应该伪造管理员身份。

本项目的硬规则是：

1. `@PreAuthorize` 默认只放在纯 HTTP Controller 或纯 HTTP 管理门面上。
2. 被 MQ 消费者、定时任务、事件回放内部流程调用的方法，禁止添加依赖当前用户 `SecurityContext` 的权限注解。
3. 业务 Service 负责业务不变量、对象归属、状态机和幂等，不负责判断某个 HTTP 管理员是否拥有菜单权限。
4. 如果一个管理接口和内部流程需要复用同一能力，拆成“带授权的 HTTP 门面 + 不依赖 SecurityContext 的内部应用服务”。
5. 不使用 run-as、手工向 `SecurityContextHolder` 塞入 `SYSTEM_ADMIN` 等方式绕过权限；内部调用的可信边界由消息来源、Inbox 幂等、状态机和专用方法共同保证。

推荐调用结构：

```text
Admin Controller（@PreAuthorize）
  -> Admin HTTP Facade（可选，仅被 Controller 调用）
       -> Internal Application Service（无用户权限注解）

RabbitMQ Consumer / @Scheduled
  -> Internal Application Service（无用户权限注解）
```

对象所有权检查仍然必须保留在业务层，例如“只能删除自己的帖子”“只能由问题作者采纳回答”。这类检查依赖显式 `actorUserId` 或 `CurrentUserProvider`，与管理员 RBAC 不是同一件事。

---

## 五、目标架构

### 1. HTTP 请求链路

```text
CorsFilter
  -> RequestIdFilter
  -> RateLimitFilter（仅登录等入口级规则）
  -> OptionalJwtAuthenticationFilter
       -> TokenAuthenticationService
            -> JwtUtil
            -> Redis 当前 Token
            -> UserMapper / RoleMapper
            -> 封禁状态
       -> SecurityContext
       -> BaseContext 兼容写入
  -> Spring Security URL 授权
  -> @PreAuthorize 方法授权
  -> Controller / Service
  -> AdminAuditAspect（仅标记的高风险管理操作）
```

### 2. WebSocket 链路

```text
HTTP Upgrade /ws
  -> 只允许配置中的前端 Origin
  -> STOMP CONNECT
       -> WebSocket ChannelInterceptor
       -> TokenAuthenticationService
       -> 绑定 Authentication/Principal
  -> 用户订阅 /user/queue/notifications
```

HTTP Filter 和 STOMP ChannelInterceptor 是两个入口，但共用 `TokenAuthenticationService`，这就是本项目“统一鉴权”的边界。

### 3. 建议新增的后端结构

```text
com.quanta.demo0.security
├── SecurityConfiguration
├── OptionalJwtAuthenticationFilter
├── TokenAuthenticationService
├── AuthenticatedUser
├── SecurityAuthenticationEntryPoint
├── SecurityAccessDeniedHandler
├── CurrentUserProvider
├── PermissionConstants
└── SecurityProperties

com.quanta.demo0.audit
├── AdminAudit
├── AdminAuditAspect
├── AdminAuditService
└── AdminAuditServiceImpl

com.quanta.demo0.ratelimit
├── RateLimit
├── RateLimitAspect
├── RedisRateLimitService
└── RateLimitProperties
```

命名和分层遵循当前项目的 Controller、Service、ServiceImpl、Mapper、DTO、VO、Result/PageResult 风格，不在这次改造中顺带重构其他模块。

### 4. 当前内部线程调用白名单

下列调用已经从当前源码确认。表内 Service 方法不得直接增加 `@PreAuthorize`、`hasRole` 或 `hasAuthority`：

| 内部入口 | 当前调用的 Service | 关键用途 | 权限注解位置 |
| --- | --- | --- | --- |
| `ModerationConsumer` | `ContentModerationService` | 调用内容审核供应方 | 禁止放在该 Service |
| `ModerationConsumer` | `ModerationResultService` | 更新审核结果并完成 Inbox | 禁止放在该 Service |
| 六个 MQ 消费者 | `InboxEventService` | 抢占、成功、重试和 DEAD | 禁止放在该 Service |
| `NotificationConsumer` | `NotificationConsumeService` | 保存通知并完成 Inbox | 禁止放在该 Service |
| `FeedPushConsumer`、`FeedDeleteConsumer` | `FollowService` | 校准关注 Feed | 禁止放在内部校准方法 |
| `HotScoreUpdateConsumer` | `ContentService` | 校准内容热度 | 禁止放在内部校准方法 |
| `SearchReconcileConsumer` | `SearchReconcileService` | 校准 ES 索引 | 禁止放在该 Service |
| `OutboxDispatcher` | `OutboxEventService` | 抢占、发送结果、重试和 DEAD | 禁止放在该 Service |
| `OutboxMaintenanceService` 定时任务 | Outbox/Inbox Mapper | 清理和监控 | 不引入用户权限上下文 |

阶段 0 还要通过调用搜索补齐未来新增的 `@RabbitListener`、`@Scheduled`、`ApplicationEventListener` 和异步任务。每次准备给 Service 加方法安全注解前，必须先证明它只会被 HTTP 管理入口调用；无法证明时，权限注解放 Controller。

---

## 六、权限模型与权限矩阵

### 1. 第一版角色

| 角色 | 定位 |
| --- | --- |
| `USER` | 已登录普通用户 |
| `VERIFIED_USER` | 已通过校友身份认证的用户 |
| `CONTENT_AUDITOR` | 内容、回答、评论和举报审核人员 |
| `OPERATIONS_ADMIN` | 用户治理、身份审核和事件只读运维人员 |
| `SUPER_ADMIN` | 拥有角色管理、事件重放和审计查看等最高风险权限 |

为保持现有管理员可继续使用，迁移脚本将当前 `is_admin = 1` 的账号默认授予 `SUPER_ADMIN`。验证稳定后，管理端再把不需要最高权限的账号调整为较小角色。

### 2. 第一版权限常量

| 权限 | 允许的操作 |
| --- | --- |
| `CONTENT_READ_ADMIN` | 查看管理端帖子、回答、评论和举报 |
| `CONTENT_AUDIT` | 审核帖子、回答、评论和举报 |
| `CONTENT_DELETE` | 管理员删除内容 |
| `IDENTITY_AUDIT` | 审核校友身份 |
| `USER_READ_ADMIN` | 查看管理端用户信息 |
| `USER_BAN` | 封禁或解封用户 |
| `EVENT_READ` | 查看 Outbox/Inbox 和事件概览 |
| `EVENT_REPLAY` | 人工重放 DEAD 事件 |
| `AUDIT_LOG_READ` | 查看管理员审计日志 |
| `ROLE_MANAGE` | 修改管理员角色和权限 |

### 3. 角色与权限映射

| 角色 | 默认权限 |
| --- | --- |
| `CONTENT_AUDITOR` | `CONTENT_READ_ADMIN`、`CONTENT_AUDIT` |
| `OPERATIONS_ADMIN` | `USER_READ_ADMIN`、`USER_BAN`、`IDENTITY_AUDIT`、`EVENT_READ` |
| `SUPER_ADMIN` | 第一版全部管理权限 |

`EVENT_REPLAY` 不授予普通内容审核员或运营管理员，避免“能看事件”自然升级成“能重放事件”。

### 4. 用户接口分类原则

用户接口先按能力分类，不直接把现有 `@RequireAuth` 原样搬进 Spring Security：

| 分类 | 说明 | 初始处理 |
| --- | --- | --- |
| 公开接口 | 登录、开发环境 API 文档 | `permitAll` |
| 可选鉴权接口 | 推荐流、内容搜索、热门搜索 | `permitAll`，Filter 尝试认证 |
| 普通登录接口 | 个人资料、历史、通知、关注等 | `authenticated` |
| 当前安全上下文 | `GET /user/security-context` | `authenticated`，用于恢复角色和权限，不接受前端自报角色 |
| 认证校友接口 | 发布内容、回答、评论等需要社区资格的操作 | `hasRole('VERIFIED_USER')` 或对应权限 |
| 管理接口 | `/admin/**` | 先要求登录，再校验具体权限 |

在正式实施前，要把所有 Controller 路径导出成一张接口权限清单，逐条确认以下问题：

1. 内容详情、回答列表和评论列表是否真的必须认证校友才能查看。
2. 点赞、收藏、关注和举报是否要求完成校友认证，还是只要求登录。
3. 搜索历史接口是否只要求登录。
4. RAG 搜索允许匿名、登录用户还是认证校友使用。
5. Knife4j 和 Swagger 是否仅在开发、测试环境开放。

上述产品决策的默认值统一为“保持当前实际可观察行为”，不阻塞 Spring Security 主链路迁移。需要收紧或放宽权限的接口作为独立变更提交，单独修改测试和前端提示。

现有 `@RequireAuth` 拦截器未启用，所以直接启用它会改变大量线上行为。必须先完成接口矩阵和回归测试，不能把“注解存在”直接当成最终产品规则。

### 5. 管理端接口权限建议

| 接口范围 | 权限 |
| --- | --- |
| `/admin/content/**` 查询 | `CONTENT_READ_ADMIN` |
| 内容、回答、评论审核 | `CONTENT_AUDIT` |
| 管理删除内容 | `CONTENT_DELETE` |
| 举报处理 | `CONTENT_AUDIT` |
| `/admin/identityExam/**` | `IDENTITY_AUDIT` |
| `/admin/user/**` 查询 | `USER_READ_ADMIN` |
| 用户封禁、解封 | `USER_BAN` |
| `/admin/moderation/**` 查询 | `CONTENT_READ_ADMIN` |
| `/admin/events/**` 查询 | `EVENT_READ` |
| `/admin/events/**/replay` | `EVENT_REPLAY` |
| `/admin/audit-logs/**` | `AUDIT_LOG_READ` |
| `/admin/roles/**` | `ROLE_MANAGE` |

---

## 七、数据库改造计划

### 1. 角色表

建议新增 `user_role`：

| 字段 | 类型建议 | 说明 |
| --- | --- | --- |
| `id` | BIGINT | 主键 |
| `user_id` | BIGINT | 用户 ID |
| `role_code` | VARCHAR(64) | 角色代码 |
| `created_by` | BIGINT | 授权管理员 |
| `created_at` | DATETIME | 授权时间 |

约束和索引：

- 唯一键 `uk_user_role(user_id, role_code)`，保证重复授权幂等。
- 索引 `idx_user_role_role(role_code, user_id)`，支持按角色查询。
- 角色代码在 Java 枚举或常量中维护，第一版不增加动态角色配置表。

### 2. 管理员审计日志表

建议新增 `admin_audit_log`：

| 字段 | 类型建议 | 说明 |
| --- | --- | --- |
| `id` | BIGINT | 主键 |
| `request_id` | VARCHAR(64) | 一次请求的关联标识 |
| `operator_id` | BIGINT | 操作管理员 ID |
| `operator_roles` | VARCHAR(512) | 操作当时的角色快照 |
| `action` | VARCHAR(64) | 操作代码 |
| `target_type` | VARCHAR(64) | 目标类型 |
| `target_id` | VARCHAR(128) | 目标 ID，兼容复合键 |
| `http_method` | VARCHAR(16) | 请求方法 |
| `request_path` | VARCHAR(512) | 请求路径，不含敏感查询值 |
| `before_summary` | TEXT | 修改前摘要 |
| `after_summary` | TEXT | 修改后摘要 |
| `result_status` | VARCHAR(16) | `SUCCESS` 或 `FAILED` |
| `error_code` | VARCHAR(64) | 失败代码 |
| `error_message` | VARCHAR(1000) | 脱敏后的失败原因 |
| `client_ip` | VARCHAR(64) | 客户端 IP |
| `user_agent` | VARCHAR(512) | User-Agent |
| `created_at` | DATETIME | 记录时间 |

建议索引：

- `idx_audit_operator_time(operator_id, created_at)`
- `idx_audit_action_time(action, created_at)`
- `idx_audit_target(target_type, target_id, created_at)`
- `idx_audit_result_time(result_status, created_at)`
- `uk_audit_request_action(request_id, action, target_type, target_id)`，是否设置唯一键要根据一次请求是否允许批量目标决定；批量操作可改用子项编号避免误去重。

### 3. 数据迁移策略

新增脚本建议命名：

- `src/main/resources/db/V_security_governance.sql`
- `src/main/resources/db/V_security_governance_verify.sql`
- `src/main/resources/db/V_security_governance_rollback.sql`

迁移步骤：

1. 创建 `user_role` 和 `admin_audit_log`。
2. 将现有 `is_admin = 1` 的用户幂等写入 `SUPER_ADMIN`。
3. 验证每个现有管理员至少拥有一个管理角色。
4. 应用层切换为角色权限判断。
5. 保留 `is_admin` 作为兼容字段，不在本阶段删除。
6. 稳定运行并确认没有旧逻辑后，后续版本再讨论移除 `is_admin`。

回滚时只回滚应用权限读取逻辑，不应直接删除已经产生的审计日志。表删除脚本只能在确认无有效数据的开发环境使用。

---

## 八、分阶段实施路线

### 阶段 0：建立安全行为基线

#### 目标

在动认证代码之前，先证明当前行为是什么，特别是把容易迁移回归的隐式行为写成测试。

#### 工作内容

1. 导出全部 Controller 路径、HTTP 方法、当前拦截规则和 `@RequireAuth` 标记。
2. 形成正式接口权限矩阵。
3. 对 `AuthInterceptor` 未注册的问题增加失败测试，确认普通登录用户目前是否能调用认证校友接口。
4. 为三类可选鉴权场景增加特征测试。
5. 为管理员、封禁、退出登录和单会话覆盖增加特征测试。
6. 为 WebSocket CONNECT 的有效、无效 Token 增加测试。
7. 扫描全部 `@RabbitListener`、`@Scheduled`、事件监听器和异步任务，登记其调用的 Service 方法，形成“内部调用禁止权限注解”清单。
8. 盘点管理端和小程序端对 HTTP 状态及 `Result.code` 的处理。当前后端同时存在“原始 HTTP 401 空响应”和“HTTP 200 + `Result.code=401`”两种行为，迁移时必须同步两端。
9. 把 `GET /user/security-context` 登记为强制登录接口，并为它定义响应字段和前端恢复流程。

#### 完成标准

- 每个公开、可选鉴权、强制登录、认证校友和管理接口都有明确分类。
- 现有行为与目标行为的差异被单独列出，不能在迁移时悄悄改变。
- `@RequireAuth` 是否失效有可重复测试证据。
- 每个准备添加 `@PreAuthorize` 的方法都能证明不存在 MQ、定时任务或其他无 SecurityContext 的合法调用方。
- 管理端和小程序端对 401、403、429 的现状与目标行为都有清单。

### 阶段 1：抽取统一 Token 认证服务

#### 目标

先做行为不变的纯重构，消除 HTTP 和 WebSocket 重复的 JWT、Redis 和封禁逻辑。

#### 建议接口

```java
public interface TokenAuthenticationService {
    AuthenticatedUser authenticate(String token);
}
```

`AuthenticatedUser` 至少包含：

- `userId`
- `roles`
- `authorities`
- `accountStatus`
- `verified`

认证失败要区分：

- Token 缺失
- JWT 伪造
- JWT 过期
- Redis 登录态不存在
- Redis Token 不匹配
- 用户不存在
- 用户已封禁

#### 认证链路开销与认证校友缓存

- 可选鉴权接口没有 Token 时直接保持匿名，不访问 Redis 和数据库。
- 有 Token 时保留现有的一次 Redis Token校验；这不是新增开销。
- 普通受保护请求需要加载账号状态。查询应合并必要用户字段，避免分别查询账号、管理员标记和认证状态。
- `VERIFIED_USER` 使用 cache-aside：Redis Key建议为 `security:verified:{userId}`，TTL 初始设为 5 分钟并配置化。
- 身份审核通过、驳回、撤销时，在数据库事务提交后删除该缓存；缓存删除失败记录告警，最多受 TTL 限制后回源恢复。
- 管理员角色不只依赖 5 分钟缓存。角色变更必须立即清缓存，并按本计划撤销当前登录态，保证高风险权限不会在缓存窗口内继续使用。
- 认证缓存只能保存状态代码和更新时间等最小字段，不能保存证件号或认证材料。

对客户端可返回有限的统一提示，详细原因只进入脱敏日志，避免泄露账号状态。

#### HTTP 改动

`JwtTokenUserInterceptor` 暂时调用 `TokenAuthenticationService`，行为保持不变。

#### WebSocket 改动

`WebSocketConfig` 中的 `ChannelInterceptor` 调用同一个服务，成功后绑定 `Principal`，不改订阅地址和通知推送协议。

#### 完成标准

- HTTP 和 WebSocket 不再各自复制 JWT、Redis、封禁和 DB 兜底逻辑。
- 现有测试全部通过。
- WebSocket 的连接和通知推送行为不变。
- 无 Token的可选鉴权请求不会产生 Redis/DB 身份查询；认证校友缓存命中、miss 和主动失效都有测试。

### 阶段 2：引入 Spring Security，替换 HTTP 拦截器

#### 依赖和配置

增加：

- `spring-boot-starter-security`
- 测试范围的 `spring-security-test`

启用：

- `@EnableMethodSecurity`
- 无服务端 Session 的 `SessionCreationPolicy.STATELESS`
- 关闭不适用于当前 Token API 的默认表单登录和 HTTP Basic
- 根据当前接口使用方式评估并明确 CSRF 策略；当前 Token 通过自定义 Header 传递且不依赖 Cookie，可禁用 CSRF，但必须在文档中写明原因

这里的 `STATELESS` 只表示 Spring Security 不创建 `HttpSession`，不代表整个认证体系不依赖 Redis。

#### 可选鉴权 Filter

新增 `OptionalJwtAuthenticationFilter extends OncePerRequestFilter`：

1. OPTIONS 请求交给 CORS 处理。
2. 没有 Token 时不抛错，保持匿名。
3. 有 Token 时调用 `TokenAuthenticationService`。
4. 认证成功后构造 `Authentication` 写入 `SecurityContext`。
5. 同时写入 `BaseContext`，兼容现有业务代码。
6. 强制鉴权接口的无效 Token 最终由 Security EntryPoint 返回 401。
7. 可选鉴权接口的无效 Token 按基线语义降级匿名。
8. 在请求完成的 `finally` 中清理 `BaseContext`。

不能仅通过 `shouldNotFilter()` 跳过全部 `permitAll` 路径，因为可选鉴权路径仍需要在有 Token 时建立登录态。

#### URL 授权配置

至少区分：

- 开发环境公开的 Knife4j、Swagger 路径。
- `/user/login`。
- 三类可选鉴权路径。
- `/ws` 握手地址，真正认证仍在 STOMP CONNECT。
- 普通登录接口。
- `/admin/**`。

API 文档在生产环境默认关闭或限制访问，不应长期无条件 `permitAll`。

#### 统一异常响应

实现：

- `SecurityAuthenticationEntryPoint`
- `SecurityAccessDeniedHandler`

它们直接写入与现有 `Result` 一致的 JSON，并设置正确 HTTP 状态。因为 Filter 层异常不会经过 `GlobalExceptionHandler`，不能只依赖 `@RestControllerAdvice`。

#### CORS 收口

只保留一套安全配置可识别的 `CorsConfigurationSource`：

- 开发环境允许本地管理端和小程序开发需要的来源。
- 生产环境从配置读取明确的 Origin 清单。
- 不再使用 `allowedOriginPatterns("*")` 作为生产默认值。
- HTTP 与 WebSocket 使用同一份来源配置或同源配置属性。

#### 完成标准

- 删除或停用 `JwtTokenUserInterceptor` 的注册。
- 所有受保护接口统一返回带 JSON 的 401、403。
- 推荐流和搜索的匿名、登录个性化行为保持不变。
- 现有 `BaseContext` 业务代码无须大面积修改。
- Knife4j 在开发环境可用，生产环境不意外公开。

### 阶段 3：启用方法级授权和认证校友权限

#### 目标

解决 `@RequireAuth` 名义存在但拦截器未启用的问题，并逐步替换路径级 `isAdmin` 判断。

#### 工作内容

1. 根据确认后的权限矩阵，默认在 HTTP Controller 方法上增加 `@PreAuthorize`。
2. 只有经过调用图检查、能够证明仅由 HTTP 管理入口调用的 `AdminXxxService` 门面，才允许把权限注解下沉到该门面；普通领域 Service 和内部应用 Service 默认不加用户权限注解。
3. 认证校友判断先放在对应 HTTP 写接口边界，转换为 `VERIFIED_USER` 角色或对应 Authority；内部审核、通知、Feed、热度、ES 和 Outbox 方法不读取当前用户权限。
4. 管理 Controller 与内部流程需要复用能力时，拆成带授权的管理门面和无 SecurityContext 依赖的内部服务，不允许通过 run-as 或伪造系统管理员绕过。
5. 禁止同时混用含义不明的 `hasRole` 和 `hasAuthority`：
   - `hasRole('SUPER_ADMIN')` 会自动匹配 `ROLE_SUPER_ADMIN`。
   - 业务权限统一使用 `hasAuthority('EVENT_REPLAY')`。
6. 逐步删除已经被 `@PreAuthorize` 取代的 `@RequireAuth`，避免两套注解长期并存。
7. 移除 `/admin/**` 中散落的 `isAdmin` 分支，但数据库字段暂时保留兼容。

#### 方法级权限注解白名单规则

允许：

- `AdminContentController.audit()` 等纯 HTTP 管理入口。
- 经过调用图确认只被 Controller 调用的 `AdminRoleFacade.changeRoles()` 等管理门面。

禁止：

- `ContentModerationService`、`ModerationResultService`、`InboxEventService`。
- `FollowService.reconcileContentFeed()`、`ContentService.reconcileHotScore()`。
- `NotificationConsumeService`、`SearchReconcileService`、`OutboxEventService`。
- 任何由 `@RabbitListener`、`@Scheduled`、事件监听器或异步任务直接/间接调用的方法。

代码评审时新增检查项：添加 `@PreAuthorize` 的提交必须同时给出调用方搜索结果和一个“空 SecurityContext 的内部调用不受影响”测试。

#### 封禁后的 WebSocket 推送处理

当前封禁会阻止用户下一次 STOMP CONNECT，但已经建立的连接仍可能继续收到 `NotificationConsumer` 的实时推送。第一版增加低成本的强制保护：

1. 数据库通知仍然正常保存并完成 Inbox，封禁不破坏通知事实记录。
2. `NotificationConsumer` 在 `convertAndSendToUser` 前调用不依赖 SecurityContext 的 `UserAccessStateService.canReceiveRealtimePush(userId)`。
3. 该服务先检查 Redis 封禁标记，并以数据库账号状态兜底；确认封禁时跳过 WebSocket 推送，但消息仍然 ACK，不进入重试或 DEAD。
4. 跳过推送只记录脱敏日志和指标，不能把它当成消息消费失败。
5. 如果产品要求“封禁后立即关闭已建立连接”，后续增加 userId 到 WebSocket session 的索引，并在封禁事务提交后主动关闭对应 session；禁止仅靠下一次重连失败宣称已经踢下线。

当前 WebSocket 主要用于服务端通知推送，没有用户通过 STOMP 执行受保护写操作。若以后增加客户端消息入口，必须对每条消息再次校验账号状态或主动断开封禁会话。

#### 完成标准

- 普通用户、认证校友和各管理角色的权限边界有集成测试证明。
- `EVENT_READ` 用户不能执行 `EVENT_REPLAY`。
- 内容审核员不能封禁用户或修改角色。
- 降权或撤销会话后旧权限不能继续使用。
- 不依赖前端隐藏按钮保证安全。
- 六个 MQ 消费者、Outbox Dispatcher 和维护定时任务在空 SecurityContext 下仍可完成原有可靠链路测试，不出现 `AuthenticationCredentialsNotFoundException`。
- 封禁用户的数据库通知仍可可靠保存，但已建立连接不再收到新的实时推送。

### 阶段 4：管理员审计闭环

#### 需要审计的操作

第一批必须覆盖：

- 帖子、回答、评论审核。
- 管理员删除内容。
- 举报处理。
- 校友身份审核。
- 用户封禁和解封。
- Outbox/Inbox 人工重放。
- 管理员角色授予和撤销。

普通查询不默认记录审计，避免日志噪音；审计日志查询本身可以记录访问事件或只记录导出行为。

#### 实现方式

使用 `@AdminAudit` 标记高风险方法，AOP 负责采集通用请求信息和失败结果：

- 操作人
- requestId
- HTTP 方法和路径
- 客户端信息
- 操作代码
- 目标类型和目标 ID
- 执行结果和异常

业务相关的前后摘要不能盲目序列化整个参数和实体。以下字段禁止进入审计日志：

- JWT、Redis Token、微信 code
- 密钥和密码
- 完整身份证号、手机号等敏感个人信息
- 上传文件内容
- 超长正文和消息 payload

对于需要前后状态的操作，由业务层显式提供脱敏摘要并在业务事务内写入成功审计，不能依靠 AOP 盲目序列化参数，例如：

```text
用户封禁：accountStatus 0 -> 1
内容审核：PENDING -> APPROVED
事件重放：DEAD -> PENDING，原 retryCount=5
角色撤销：SUPER_ADMIN -> USER
```

#### 事务策略

成功审计：

- 高风险业务在自身事务内写入 `SUCCESS` 审计，业务数据和成功审计一起提交、一起回滚。
- 可以显式调用 `AdminAuditRecorder`，或者在事务 `BEFORE_COMMIT` 阶段处理审计事件；不能等业务提交完成后才尝试写成功审计。
- 审计插入失败时抛出异常并回滚高风险业务，这样才能真正做到 fail-closed。
- 没有事务的高风险写操作应先补充事务边界，再接入成功审计，不能用无事务的两次独立写入假装原子性。

失败审计：

- 捕获异常后使用 `REQUIRES_NEW` 独立事务写入 `FAILED`。
- 写完后重新抛出原异常，不能吞掉业务错误。

审计写入失败：

- 审核、封禁、角色修改、事件重放等管理写操作默认 fail-closed：成功审计无法写入时，业务事务也不提交。
- 失败审计使用独立事务是为了保留失败痕迹；如果失败审计本身写入失败，记录严重告警并保留原业务异常，不能用审计异常覆盖原始错误。
- 查询类接口不记录普通操作审计，因此不会因为审计系统问题阻断日常只读查询。

审计表因此是管理写操作的强依赖，必须显式治理：

- 审计 INSERT 使用明确的数据库语句超时，避免表锁或连接异常让管理请求无限等待。
- 审计失败向客户端返回可识别的“安全审计暂不可用，操作未执行”，不能伪装成审核成功。
- 记录审计写入耗时、失败次数和最后错误类型，达到阈值立即告警。
- 为 `created_at`、`operator_id`、`action` 等查询路径建立索引，分页禁止无条件全表扫描。
- 制定保留和归档策略，避免审计表无限增长反过来拖慢所有高风险操作。

#### 管理端接口

新增：

- `GET /admin/audit-logs/page`
- `GET /admin/audit-logs/{id}`

支持条件：

- 操作人
- 操作代码
- 目标类型和目标 ID
- 成功或失败
- requestId
- 时间范围

审计日志第一版只读，不提供普通删除接口。需要清理时使用明确保留期的维护任务，并禁止删除近期或安全事件日志。

#### 完成标准

- 每个高风险操作成功和失败都有测试。
- 业务回滚不会留下错误的成功记录。
- 审计记录不包含 Token、微信 code 和敏感证件信息。
- 能从管理端用 requestId 追踪一次操作。

### 阶段 5：Redis Lua 限流

#### 第一批限流对象

| 场景 | 建议维度 | 初始配置示例 |
| --- | --- | --- |
| 微信登录预检查 | IP 哈希 + 全局洪峰 | IP 每分钟 60 次、应用全局每分钟 1000 次，仅作宽松洪峰保护 |
| 微信身份解析后 | openid 的 HMAC摘要 | 5 分钟 10 次，作为主要账号维度限制 |
| 文件上传 | userId + IP | 1 分钟 20 次 |
| 内容发布 | userId | 1 分钟 5 次 |
| 评论和回答发布 | userId | 1 分钟 15 次 |
| 举报提交 | userId | 10 分钟 10 次 |
| 事件重放 | adminId | 5 分钟 3 次 |
| 用户封禁、角色修改 | adminId | 5 分钟 10 次 |

数值必须配置化，以上只作为初始值，后续根据真实日志和压测调整。

小程序的 `wx.request` 在当前项目中由用户设备直接请求后端，并不能简单认定所有流量都来自腾讯统一出口；但办公网、校园网、运营商 NAT 和代理仍可能让很多用户共享 IP。因此登录不能只按 IP 做严格限制。

登录采用两段式控制：

1. 调用微信接口前，用较宽松的 IP 哈希和应用全局阈值抵挡明显洪峰，保护微信 API 和本服务资源。
2. 用 code 换取 openid 后、创建登录态前，以 openid 的 HMAC 摘要执行较严格的账号维度限制。

微信 code 是短期一次性凭证，不适合作为稳定限流维度，也禁止写入日志或 Redis Key。openid 不以明文进入限流 Key，使用服务端密钥计算 HMAC；不能只做普通 SHA-256，避免低熵标识被离线枚举。

#### Lua 原子性

Lua 脚本一次完成：

1. 读取当前计数。
2. 计数加一。
3. 首次写入时设置过期时间。
4. 返回是否允许、剩余次数和重试等待时间。

不能把 `INCR` 和 `EXPIRE` 分成两个独立 Redis 命令，否则进程在中间失败时可能产生永不过期的限流 Key。

#### 放置位置

- 登录入口：Filter 或登录 Controller 前的专用组件。
- 用户发布、上传：`@RateLimit` AOP 或明确的 Service 层调用。
- 管理高风险操作：Service 层权限校验通过之后、业务事务开始之前限流。

注意 Spring AOP 自调用问题。同一个 Bean 内部调用自身带注解方法时不会经过代理；这类场景使用独立限流服务显式调用。

#### Redis 异常策略

- 强制鉴权接口本身已经依赖 Redis 登录态，Redis 不可用时认证失败并告警。
- 匿名查询类接口限流 Redis 异常时可暂时放行，但必须记录指标和告警。
- 事件重放、角色修改等高风险接口限流异常时 fail-closed。

#### 完成标准

- 并发请求下计数与过期时间正确。
- 超限统一返回 HTTP 429 和 `Result.code=429`。
- 不记录原始 Token、微信 code 或完整 IP 组合 Key。
- 管理端能正确展示“操作过于频繁”，不会误清空登录态。

### 阶段 6：管理端权限与审计页面

#### 登录态调整

登录后不再信任本地保存的 `isAdmin` 作为权限事实源。新增后端会话信息接口，例如：

- `GET /user/security-context`

返回：

- 当前用户基本信息
- 角色列表
- 权限列表
- 是否完成身份认证

管理端启动和刷新时通过接口恢复权限。`localStorage` 中的角色和权限只能作为短暂 UI 缓存，不能替代服务端校验。

#### 路由与菜单

每个管理路由增加 `meta.authority`：

```text
用户管理 -> USER_READ_ADMIN
内容管理 -> CONTENT_READ_ADMIN
身份认证 -> IDENTITY_AUDIT
事件中心 -> EVENT_READ
审计日志 -> AUDIT_LOG_READ
```

无权限菜单不展示；直接输入地址时显示无权限页面，而不是一律跳回登录页。

#### 按钮权限

- 审核按钮：`CONTENT_AUDIT`
- 删除按钮：`CONTENT_DELETE`
- 封禁按钮：`USER_BAN`
- 重放按钮：`EVENT_REPLAY`
- 角色修改按钮：`ROLE_MANAGE`

前端按钮控制只改善体验。即使用户手工调用 API，后端仍必须返回 403。

#### 401 与 403 行为

- 401：清理本地会话并跳转登录页。
- 403：保留登录态，展示无权限提示或无权限页面。
- 429：保留登录态，展示服务端返回的等待提示。

当前管理端已有这一基础区分，改造时继续保持，不能把 403 也当成登录失效。

#### 小程序请求层同步改造

当前小程序 `miniprogram/utils/request.ts` 同时识别 HTTP 401 和 `Result.code=401`，但 HTTP 403、429 会先落入通用非 2xx 分支，被包装成“网络异常”，并丢失服务端业务提示。安全契约切换时必须同步修改：

- HTTP 401 或 `Result.code=401`：清理 Token并进入登录流程。
- HTTP 403 或 `Result.code=403`：不清理 Token，返回 `forbidden`，由页面提示无权限或未完成认证。
- HTTP 429 或 `Result.code=429`：不清理 Token，返回 `rateLimited`，优先展示服务端 message 和可选 `Retry-After`。
- 其他非 2xx：尽量读取标准 `Result` 的 message，再降级成网络错误文案。

只修改 TypeScript 源文件并通过项目现有构建流程生成 JavaScript 和 `dist`，不把手工修改编译产物当成源代码修复。管理端 Axios 拦截器也增加 429 的独立分支。

#### 审计日志页面

沿用现有 `PageHeader`、表格、分页、筛选和抽屉详情风格，提供：

- 时间范围、操作人、动作、目标类型、结果筛选。
- 操作人、动作、目标、结果、时间列表列。
- 详情抽屉展示前后摘要、requestId、客户端信息和失败原因。
- 敏感字段在后端脱敏，前端不承担最后一道脱敏责任。

#### 完成标准

- 不同权限账号看到的菜单和按钮正确。
- 手工请求无权接口仍返回 403。
- 降权或强制下线后刷新页面能立即反映。
- 管理端生产构建通过，并完成真实浏览器验证。
- 小程序对 401、403、429 分别表现为重新登录、保留会话的权限提示、保留会话的限流提示，并重新生成 `dist`。

### 阶段 7：清理旧安全逻辑与文档收口

完成新链路验证后：

1. 删除 `JwtTokenUserInterceptor` 的旧注册和重复实现。
2. 删除已被替代的 `AuthInterceptor` 和 `@RequireAuth`，或将注解明确改造为方法安全元注解，不能保留失效代码。
3. 清理 JWT 中不再使用的 `isAdmin` claim；兼容窗口内只读不信任。
4. 清理 `WebMvcConfiguration` 中重复 CORS 配置。
5. 将 Knife4j、Swagger 的开放策略按环境配置。
6. 更新 API 文档、启动说明、测试账号和权限说明。
7. 在 `细节优化文档.md` 中补充安全治理复盘和面试话术，但只记录实际完成并验证的内容。

---

## 九、测试计划

### 1. 单元测试

#### TokenAuthenticationService

- 正常 JWT + Redis Token一致。
- JWT 签名错误。
- JWT 过期。
- Redis Key不存在。
- Redis Token不一致。
- 用户不存在。
- 用户封禁。
- 普通用户、认证校友和管理员权限加载正确。
- JWT 中伪造 `isAdmin=1` 不会获得管理权限。

#### 权限映射

- 每个角色映射到正确权限。
- `CONTENT_AUDITOR` 没有 `EVENT_REPLAY`。
- `OPERATIONS_ADMIN` 没有 `ROLE_MANAGE`。
- `SUPER_ADMIN` 拥有第一版全部权限。

#### 限流

- 第一次访问创建计数和 TTL。
- TTL 不会因为后续请求不断重置，除非算法明确要求滑动窗口。
- 达到阈值后拒绝。
- 时间窗口结束后恢复。
- 多线程并发不会突破允许数量。

### 2. MockMvc 安全集成测试

至少覆盖：

1. 无 Token访问强制登录接口返回 HTTP 401 + JSON `code=401`。
2. 伪造 Token返回 401。
3. 过期 Token返回 401。
4. Redis Token失效返回 401。
5. 普通用户访问管理接口返回 403，且不会清除其正常用户登录态。
6. 有效 Token无权限执行事件重放返回 403。
7. 可选接口无 Token访问成功。
8. 可选接口有效 Token能读取个性化状态。
9. 可选接口无效 Token按第一阶段约定降级匿名。
10. CORS 允许配置中的 Origin，拒绝未配置 Origin。
11. 开发环境文档路径可访问，生产 Profile 不公开。
12. 429 返回体与前端契约一致。
13. `GET /user/security-context` 匿名返回 401，登录后只返回服务端加载的角色和权限。
14. 管理权限 Controller 被保护，但对应内部应用 Service 在空 SecurityContext 下仍可被合法内部调用。

### 3. MySQL 与 Redis Testcontainers 集成测试

现有测试已经使用真实 MySQL 和 RabbitMQ。本阶段新增真实 Redis 容器测试，不能只 Mock `StringRedisTemplate`。

重点覆盖：

- 登录写入 7 天 Token。
- 新登录覆盖旧 Token。
- 退出登录撤销 Token。
- 封禁删除 Token并写封禁状态。
- 管理员角色变更后权限立即变化。
- 角色变更清理权限缓存和登录态。
- 认证校友状态缓存命中时不查认证表，缓存 miss 回源数据库，审核状态变更后主动失效。
- `user_role` 唯一键保证重复授权幂等。
- Lua 限流在并发条件下不超发。
- Redis 恢复后认证行为正常。

### 4. WebSocket 测试

- 无 Token的 CONNECT 被拒绝。
- 伪造 Token被拒绝。
- Redis Token不一致被拒绝。
- 封禁用户被拒绝。
- 有效用户连接成功并绑定正确 `Principal`。
- 连接建立后用户被封禁时，下一次重连必须失败。
- 用户在已建立连接后被封禁，新的实时通知不再推送，但数据库通知仍然保存成功且 MQ 正常 ACK。
- 如要求已建立连接即时断开，需要额外维护 userId 到 session 的映射并主动关闭；没有实现前不宣称已经踢掉连接。
- 通知仍能发送到 `/user/queue/notifications`。

### 5. 审计测试

- 每种高风险操作成功后产生一条审计记录。
- 操作失败也产生 `FAILED` 记录。
- 业务事务回滚不会记录错误的 `SUCCESS`。
- 审计写入失败时，高风险接口按配置 fail-closed。
- Token、微信 code、证件号等敏感字段不会进入审计表。
- 同一 requestId 可以关联业务日志和审计记录。
- 无 `AUDIT_LOG_READ` 权限无法查看审计记录。

### 6. 管理端测试

- 未登录跳转登录页。
- 401 清理登录态并跳转。
- 403 保留登录态并展示无权限状态。
- 429 不清理登录态。
- 不同角色菜单和按钮正确。
- 手工输入无权限路由无法进入业务页面。
- 审计列表筛选、分页、详情抽屉正确。
- 事件只读用户看不到重放按钮。
- 生产构建通过。
- 小程序请求层分别识别 HTTP 和 Result 形式的 401、403、429，并完成源码构建和 `dist` 更新。

### 7. 内部调用安全回归测试

在清空 `SecurityContextHolder` 和 `BaseContext` 后，分别验证：

- `ModerationConsumer` 可以调用审核与结果落库 Service。
- Feed 推送、Feed 删除、热度更新和 ES 校准消费者正常完成 Inbox。
- `NotificationConsumer` 正常保存通知；封禁用户只跳过实时推送，不把 Inbox 标记为失败。
- `OutboxDispatcher` 能抢占、发送、重试和标记 DEAD。
- `OutboxMaintenanceService` 的清理和监控任务正常执行。

测试失败时首先检查是否误把 `@PreAuthorize` 加到了内部 Service，而不是给消费者伪造管理员 SecurityContext。

### 8. 真实联调场景

至少准备四个测试账号：

- 普通用户
- 认证校友
- 内容审核员
- 超级管理员

实际联调：

1. 普通用户登录并浏览、互动。
2. 匿名和登录用户访问推荐流，比较点赞态。
3. 内容审核员审核内容，但无法重放事件。
4. 超级管理员重放 DEAD 事件，管理端可查到成功审计。
5. 将管理员降权或强制下线，旧页面下一次请求立即表现为 401 或 403，符合既定语义。
6. 连续触发限流，管理端和小程序收到正确提示。
7. WebSocket 通知在改造前后保持可用。

---

## 十、提交与回滚策略

每个阶段独立提交，建议顺序：

1. `test: lock current authentication behavior`
2. `refactor: share token authentication across http and websocket`
3. `feat: introduce spring security authentication chain`
4. `feat: add role based method authorization`
5. `feat: add administrator audit trail`
6. `feat: add redis lua rate limiting`
7. `feat: add admin permission and audit views`
8. `docs: document security governance and acceptance evidence`

每次提交都必须满足：

- 只提交当前阶段相关文件。
- 后端编译通过。
- 当前阶段新增测试通过。
- 原有可靠性测试继续通过。
- 不使用 `git add .` 或 `git add -A`，避免混入用户现有改动。

回滚原则：

- 阶段 1 是行为不变重构，可以直接回滚代码。
- 阶段 2 切换 Spring Security 时，旧拦截器在验证完成前保留源码但不双重启用。
- 禁止在生产同时启用两套鉴权链，否则容易重复查询、重复清理上下文和产生不一致状态码。
- 数据库新增表采用向前兼容方式，应用回滚时保留角色和审计数据。
- 角色切换前确保现有管理员已经迁移为 `SUPER_ADMIN`，避免所有管理员被锁在系统外。

---

## 十一、风险与应对

### 1. 推荐流丢失点赞态

原因：把可选鉴权路径简单配置为公开，同时认证 Filter 未解析 Token。

应对：Filter 对所有可能携带 Token 的请求执行尝试认证，`permitAll` 只负责授权放行；增加匿名和登录双场景测试。

### 2. 推荐流突然要求登录

原因：将可选路径误配置为 `authenticated()`。

应对：单独维护 `OPTIONAL_AUTH_PATHS`，并用 MockMvc 锁定无 Token返回 200。

### 3. 启用认证校友权限后大量接口突然 403

原因：当前 `@RequireAuth` 拦截器未注册，历史行为与注解表面含义不同。

应对：先完成接口权限矩阵；发布、回答、评论等写操作优先启用，读取接口逐项确认，不能一次照搬全部注解。

### 4. 管理员降权不生效

原因：把 JWT 中的 `isAdmin` 或长期角色 claim 当成权限事实源。

应对：权限以 DB 为准；若增加 Redis 权限缓存，角色修改必须清缓存；高风险降权同时撤销登录态。

### 5. 业务代码拿不到当前用户

原因：只写 `SecurityContext`，遗漏 `BaseContext` 兼容。

应对：认证 Filter 双写上下文，并为现有核心 Service 增加回归测试。

### 6. ThreadLocal 身份串线

原因：请求异常时没有清理 `BaseContext`。

应对：在 Filter 的 `finally` 中统一清理，测试连续模拟不同用户请求。

### 7. 方法权限注解打断 MQ 和定时任务

原因：把 `@PreAuthorize` 加到消费者或定时任务复用的 Service，内部线程没有 SecurityContext，调用时抛出 `AuthenticationCredentialsNotFoundException`，继而触发 Inbox 重试和 DEAD。

应对：权限注解默认放 HTTP Controller；内部 Service 禁止依赖当前用户权限；通过调用方清单和空 SecurityContext 回归测试把关，绝不通过伪造系统管理员身份修补。

### 8. 认证校友缓存产生短暂越权

原因：只依赖 5 分钟 TTL，没有在审核状态变更后主动失效。

应对：数据库是事实源，审核事务提交后删除缓存；权限收紧场景清缓存失败必须告警，必要时同时撤销登录态。

### 9. 登录 IP 限流误伤共享网络用户

原因：把 IP 当成唯一账号维度，校园网、办公网和运营商 NAT 下多人共享出口。

应对：IP 只做宽松洪峰保护；微信换取身份后使用 openid 的 HMAC摘要作为主要限制维度，阈值配置化并观察误拦截率。

### 10. 封禁用户仍收到实时推送

原因：封禁只影响 Redis Token 和下一次 CONNECT，已建立 STOMP 连接仍存在。

应对：NotificationConsumer 推送前检查账号状态，封禁时只跳过实时推送而不破坏数据库通知和 Inbox；若要求立即断开，再增加 session 索引和主动关闭。

### 11. WebSocket 登录全部失败

原因：误以为 HTTP Security Filter 会处理 STOMP CONNECT，或迁移时遗漏 `/ws` 握手放行。

应对：保留 ChannelInterceptor，共用认证服务；单独测试握手和 CONNECT。

### 12. 管理端收到空 401/403

原因：安全异常发生在 Filter 层，未进入 `GlobalExceptionHandler`。

应对：自定义 EntryPoint 和 AccessDeniedHandler，验证 HTTP 状态与 Result JSON。

### 13. 审计日志记录了业务最终回滚的“成功”

原因：AOP 在方法返回后立即写成功，但外层事务随后回滚。

应对：成功审计与业务数据在同一事务内提交和回滚；失败记录使用独立事务。高风险写操作如果还没有事务，要先补齐事务边界。

### 14. 审计日志泄露敏感信息

原因：直接序列化请求参数、实体或异常堆栈。

应对：白名单摘要、长度限制、字段脱敏和测试扫描，禁止记录 Token、微信 code、密钥和完整证件信息。

### 15. 前端把 403 或 429 当成网络异常或登录过期

原因：前端全局异常处理没有区分身份失效、权限不足和频率限制；小程序当前对非 2xx 的 403、429 会先进入通用网络错误分支。

应对：管理端和小程序端同步契约；401 才清理会话，403 保留会话并展示无权限，429 保留会话并展示频率限制及 `Retry-After`。

---

## 十二、阶段完成标准

只有同时满足以下条件，安全治理才能算完成：

1. HTTP 鉴权统一由 Spring Security Filter Chain 管理。
2. 可选鉴权接口匿名、有效 Token、无效 Token三种行为都有测试。
3. `SecurityContext` 与 `BaseContext` 兼容层稳定，现有业务无大面积回归。
4. WebSocket 继续由 ChannelInterceptor 鉴权，并与 HTTP 共用认证服务。
5. JWT 中的管理员声明不再作为权限事实源。
6. 普通用户、认证校友、内容审核员、运营管理员和超级管理员权限边界明确。
7. `EVENT_REPLAY` 与 `CONTENT_AUDIT` 真正分离。
8. 所有安全异常返回统一 JSON，并正确区分 401、403、429。
9. 生产 CORS 和 WebSocket Origin不再允许任意来源。
10. 高风险管理操作成功和失败都能查询审计记录。
11. 审计记录无 Token、密钥、微信 code 和敏感身份信息。
12. 登录、上传、发布、举报和事件重放具备原子限流。
13. 管理端按权限展示菜单和按钮，且后端能阻止绕过前端的直接请求。
14. 后端原有可靠性测试、安全新增测试、管理端构建和真实浏览器联调全部通过。
15. 文档只记录已经实现并验证的事实，不把计划中的能力写成已完成。
16. 权限注解没有进入 MQ、Outbox、定时任务复用的内部 Service；空 SecurityContext 的内部可靠链路测试通过。
17. 认证校友缓存有短 TTL、主动失效和数据库回源证据，管理员权限收紧不会等待缓存自然过期。
18. 封禁用户不再收到新的 WebSocket 实时通知，同时数据库通知和 Inbox 可靠性不受影响。
19. 管理端和小程序端都正确区分 HTTP/Result 两种形式的 401、403、429。
20. 登录限流采用宽松 IP 洪峰保护和 openid HMAC账号维度，不把共享 IP 当作唯一身份。

---

## 十三、面试表达主线

### 1. 一分钟版本

项目最初使用自定义拦截器完成 JWT 和 Redis Token校验，能够支持退出登录和封禁后的即时失效，但随着匿名推荐、认证校友、管理端和 WebSocket 场景增加，鉴权逻辑开始分散，管理员权限也只有一个 `isAdmin` 标记。我先通过测试锁定匿名与登录态并存的接口行为，再抽出 HTTP 和 STOMP 共用的 Token 认证服务，使用 Spring Security 统一 HTTP 身份上下文、401/403 和 HTTP 入口权限，同时保留 `BaseContext` 兼容层控制迁移风险。因为 MQ 和定时任务没有 SecurityContext，我没有把权限注解盲目下沉到共享 Service，而是拆分带授权的管理入口和无用户上下文的内部应用服务。之后我把事件重放等高风险权限从普通审核权限中拆开，并为审核、封禁、重放和角色修改补充了事务一致的审计和 Redis Lua 原子限流。

### 2. 为什么保留 Redis Token

JWT 负责对身份声明签名，Redis 负责当前会话控制。项目每次请求都会读取 Redis，因此我没有把它描述成纯无状态认证。这个方案的收益是退出登录、封禁、新登录覆盖旧会话和高风险降权能够即时生效，代价是多一次 Redis 读取和对共享 Redis 的依赖。对于当前社区项目，这个安全性和实现复杂度的取舍是可接受的。

### 3. 为什么不全量替换 BaseContext

现有业务有约 59 处调用 `BaseContext.getCurrentId()`。一次性改成 `SecurityContextHolder` 会扩大改动面，而且会让业务层直接依赖安全框架。我选择让认证 Filter 同时填充 SecurityContext 和业务上下文，先完成安全链路切换，再通过 `CurrentUserProvider` 渐进收口。这体现的是迁移风险控制，不是为了追求形式上的框架统一。

### 4. 为什么 WebSocket 没有直接套 HTTP Filter

HTTP Upgrade 之后的 STOMP CONNECT 是消息，不是普通 Servlet 请求。HTTP 和 WebSocket 应共享 Token 解析、Redis 校验、封禁和权限加载能力，但保留各自正确的执行入口：HTTP 使用 OncePerRequestFilter，STOMP 使用 ChannelInterceptor。

### 5. 为什么事件重放要独立权限

事件查看是只读运维能力，事件重放会重新触发消费者并改变通知、Feed、热度或 ES 等派生状态，风险明显高于普通内容审核。因此 `EVENT_READ`、`CONTENT_AUDIT` 和 `EVENT_REPLAY` 必须拆开，并对重放增加限流、状态前置检查和管理员审计。

### 6. 还存在什么边界

第一版仍然使用单 Redis Key保存一个用户的有效 Token，因此属于单会话模型；权限第一版以数据库为准，尚未做复杂权限缓存；已经建立的 WebSocket 连接在用户封禁后是否需要主动踢下线，也需要额外的会话索引支持。这些都是明确记录的边界，而不是假装系统已经解决所有安全问题。

### 7. 为什么权限注解没有全部放到 Service

项目的审核、通知、Feed、热度、ES 和 Outbox 链路会在 RabbitMQ 或定时任务线程中调用共享 Service，这些线程没有 HTTP 用户的 SecurityContext。如果把 `@PreAuthorize` 直接加到共享 Service，会让合法内部调用失败并进入重试或 DEAD。我将管理员 RBAC 放在 HTTP Controller 或专用管理门面，把无用户上下文的内部应用服务保持为纯业务能力；业务层仍然负责对象归属、状态机和幂等，而不是依赖伪造的系统管理员身份。

---

## 十四、建议最终交付物

完成实施后，应至少交付：

- 安全权限矩阵文档。
- 安全治理数据库脚本、验证脚本和回滚说明。
- Spring Security 配置与公共认证服务。
- HTTP 可选鉴权 Filter。
- WebSocket 共用认证改造。
- 角色、权限和会话信息接口。
- 管理员审计表、后端接口和管理端页面。
- Redis Lua 限流脚本和配置。
- 后端单元、MockMvc、MySQL/Redis Testcontainers、WebSocket 测试。
- 管理端构建结果和真实浏览器验收记录。
- 更新后的项目细节优化复盘与面试话术。

实施时严格按阶段推进：先用测试证明现状，再做行为不变的公共认证重构，然后切换 Spring Security，最后增加权限、审计和限流。任何阶段发现行为与权限矩阵不一致，都先修正当前阶段并重新验证，不带着未知回归进入下一阶段。
