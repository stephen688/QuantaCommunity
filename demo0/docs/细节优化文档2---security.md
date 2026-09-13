# 项目安全治理优化复盘（面试版）

> 本文不是简单罗列“我用了哪些技术”，而是记录我在安全治理中发现了什么问题、为什么原来的实现不可靠、我怎样在不推翻现有微信登录和业务结构的前提下完成统一认证与细粒度授权，以及最终如何证明改造有效。
>
> 本次改造主要分成三层：
>
> 1. 先把分散在拦截器、WebSocket、业务代码中的鉴权逻辑收口成一条统一链路；
> 2. 再把“是不是管理员”这种粗粒度判断，拆成“身份、角色、权限”三层模型；
> 3. 最后给高风险管理操作补上事务一致的审计和 Redis 原子限流，并同步改造管理端、小程序两端的安全契约。

## 一、我对问题的总体判断

这个项目原来的登录和鉴权是能跑的，但只能回答“你登录了吗”和“你是不是管理员”这两个问题。随着匿名推荐、认证校友、WebSocket 通知和管理端场景增加，我看到了三个层次的问题：

1. **认证入口分散**：HTTP 鉴权由自定义拦截器负责，WebSocket 在 STOMP `CONNECT` 阶段又复制了一份 JWT、Redis、封禁校验逻辑，两边各自维护，容易不一致；
2. **权限粒度太粗**：管理员权限只有一个 `is_admin` 字段，无法区分内容审核、用户封禁、事件重放、审计查看这些风险差异很大的操作；更严重的是，`@RequireAuth` 对应的拦截器并没有注册，注解存在但认证校友限制可能一直没有生效；
3. **高风险操作不可追溯、不受控**：审核、封禁、重放、角色修改这些写操作没有独立审计，也没有频率控制，出了问题是“谁在什么时候做了什么”都查不到。

我的改造顺序也按这三个层次展开。先统一认证链路，再建立权限模型，最后补审计和限流，并且每一步都先把当前行为锁进测试，避免迁移过程悄悄改变线上行为。

---

## 二、优化一：抽取统一 Token 认证服务

### 1. 原来的问题在哪里

HTTP 和 WebSocket 是两个独立入口，但都需要做同一件事：解析 JWT、校验签名和有效期、比对 Redis 中的当前有效 Token、检查用户是否被封禁、加载账号状态。这些逻辑在两端各写了一份，新增一个“认证校友缓存”或者“封禁标记”时，很容易只改一边。

另一个隐患是：微信登录、JWT 解析、Redis 会话控制这些环节各自抛出的异常含义不同，散落的代码容易把“Token 过期”和“用户被封禁”混在一起返回 401。

### 2. 我的优化方式

我抽出一个公共的 `TokenAuthenticationService`，两端都只调用它：

```text
HTTP 请求 / STOMP CONNECT
    ↓
TokenAuthenticationService.authenticate(token)
    ├── JwtUtil 解析并校验签名、有效期
    ├── Redis 比对当前有效 Token（支持退出登录、顶号、封禁即时失效）
    ├── 加载用户状态：账号状态、认证校友标记
    └── 加载角色和权限列表
    ↓
返回 AuthenticatedUser { userId, roles, authorities, verified, accountStatus }
```

`AuthenticatedUser` 不再是“登录了没有”这种布尔判断，而是一个携带完整身份信息的认证结果对象。认证失败的原因在内部被区分开（Token 缺失、JWT 伪造、JWT 过期、Redis 登录态不存在、Redis Token 不匹配、用户不存在、用户封禁），但对外只返回统一提示，详细原因进入脱敏日志。

### 3. 为什么这样设计

我的判断是：**HTTP 和 WebSocket 应该共享“认证能力”，但保留各自正确的“执行入口”**。HTTP 走 `OncePerRequestFilter`，WebSocket 走 `ChannelInterceptor`，因为 STOMP CONNECT 是消息而不是普通 Servlet 请求，不能生硬套用 HTTP Filter Chain。把公共逻辑抽成服务之后，两端的行为永远一致，这就是“统一鉴权”的边界。

### 4. 优化后的好处

- HTTP 和 WebSocket 不再各写一份 JWT、Redis、封禁和数据库兜底逻辑；
- 新增一种认证失败原因时只改一个服务；
- 认证校友状态使用短 TTL 缓存（`security:verified:{userId}`），命中不查库、miss 回源数据库、审核变更后主动失效；
- 后续迁移到 Spring Security 时，这条认证服务被直接复用，改动面最小。

---

## 三、优化二：引入 Spring Security 统一 HTTP 鉴权链路

### 1. 原来的问题在哪里

原来的自定义拦截器只做“有没有 Token、Token 对不对”，不负责 URL 级授权；管理端判断散落在 `if (isAdmin == 1)` 分支里。而且 Filter 层的鉴权失败返回的是空 HTTP 401，管理端和小程序端只能靠猜。

### 2. 我的优化方式

我引入 Spring Security，但保留了项目的“JWT 签名凭证 + Redis 有状态会话”混合模型，没有把它包装成纯无状态 JWT：

- `SessionCreationPolicy.STATELESS`：只表示 Spring Security 不创建 `HttpSession`，Redis 登录态照常工作；
- 关闭表单登录和 HTTP Basic，禁用 CSRF 并在文档中写明原因（Token 通过自定义 Header 传递，不依赖 Cookie）；
- 新增 `OptionalJwtAuthenticationFilter`：

```text
1. OPTIONS 预检请求交给 CORS 处理
2. 没有 Token：不抛错，保持匿名
3. 有 Token：调用 TokenAuthenticationService
4. 认证成功：写入 SecurityContext（给 Spring Security 授权）
5. 同时写入 BaseContext（兼容现有约 59 处 getCurrentId() 业务代码）
6. 请求结束 finally 中清理两个上下文，防止 ThreadLocal 串线
```

这个 Filter 不能通过 `shouldNotFilter()` 跳过所有公开路径，因为“可选鉴权接口”虽然放行匿名访问，但携带有效 Token 时仍然需要建立登录态返回个性化数据。

### 3. 为什么保留 Redis Token 而不是纯 JWT

JWT 负责对身份声明签名，Redis 负责会话控制。每次请求都要比对 Redis 中的当前有效 Token，换来的是**退出登录、新登录顶掉旧会话、封禁、高风险降权都能即时生效**。这是安全性和实现复杂度的取舍：多一次 Redis 读取，但获得了有状态的会话撤销能力。对当前项目，这个取舍是值得的。

### 4. 为什么兼容层保留 BaseContext

现有业务有约 59 处调用 `BaseContext.getCurrentId()`。一次性全部改成 `SecurityContextHolder` 会扩大改动面，还让业务层直接依赖安全框架。所以我让认证 Filter 同时填充两个上下文，先完成安全链路切换，业务层通过 `CurrentUserProvider` 渐进收口。这是迁移风险控制，不是为了形式上的框架统一。

### 5. 优化后的好处

- 所有受保护接口统一由 Spring Security Filter Chain 管理；
- 推荐流、搜索等可选鉴权接口的“匿名可访问、登录返回个性化”语义通过测试锁定，没有回归；
- 旧拦截器 `JwtTokenUserInterceptor` 被完全删除，不再存在两套鉴权规则并存的问题；
- `BaseContext` 业务代码无须大面积修改。

---

## 四、优化三：401 / 403 / 429 统一契约

### 1. 原来的问题在哪里

安全异常发生在 Filter 层，根本不会进入 `@RestControllerAdvice`。原来的结果是：有的接口返回空 HTTP 401，有的返回 HTTP 200 + `code=401`，管理端和小程序端无法形成统一处理规则。更严重的是，前端把 403、429 当成“网络异常”，导致权限不足和频率限制都显示成错误提示。

### 2. 我的优化方式

我实现 `SecurityAuthenticationEntryPoint` 和 `SecurityAccessDeniedHandler`，在 Filter 层直接写入与现有 `Result` 完全一致的 JSON，并设置正确的 HTTP 状态：

| 场景 | HTTP 状态 | 响应体 |
| --- | --- | --- |
| 没有 Token / JWT 无效 / Redis Token 不匹配 / 封禁强制下线 | 401 | `Result.error(401, message)` |
| 已登录但权限不足 | 403 | `Result.error(403, message)` |
| 触发限流 | 429 | `Result.error(429, message)`，附带 `Retry-After` 头 |

### 3. 为什么不能只靠 @RestControllerAdvice

`@RestControllerAdvice` 只能捕获进入 DispatcherServlet 之后抛出的异常。Filter 层的认证失败、权限拒绝和限流都在 Servlet 执行链的更前面，必须由自定义 EntryPoint / Handler 直接写响应。

### 4. 优化后的好处

- 前后端对 401、403、429 有了唯一、稳定的语义：401 才清理会话，403 保留会话展示无权限，429 保留会话展示频率限制；
- 小程序请求层不再把 403、429 误包装成“网络异常”，能读到服务端真实 message。

---

## 五、优化四：从 isAdmin 到“身份、角色、权限”三层模型

### 1. 原来的问题在哪里

`is_admin` 只能回答“是不是管理员”。内容审核员、运营管理员、超级管理员的风险边界完全不同，但原来共用同一个标记，等于一个普通审核员也能封禁用户、重放事件、查看审计。而且权限事实源放在 JWT 里，登录那一刻是什么样就永远是那样，降权不生效。

### 2. 我的优化方式

新增 `user_role` 表，建立“角色 -> 权限”的映射：

- 角色：`USER`、`VERIFIED_USER`、`CONTENT_AUDITOR`、`OPERATIONS_ADMIN`、`SUPER_ADMIN`；
- 权限：`CONTENT_AUDIT`、`CONTENT_DELETE`、`USER_BAN`、`IDENTITY_AUDIT`、`EVENT_REPLAY`、`ROLE_MANAGE`、`AUDIT_LOG_READ` 等，收敛为 `PermissionConstants` 常量；
- 迁移脚本把现有 `is_admin = 1` 的账号幂等写入 `SUPER_ADMIN`，保证原有管理员迁移后仍然可用；
- `user_role` 建立 `(user_id, role_code)` 唯一键，重复授权天然幂等。

关键设计是 `EVENT_REPLAY` 与 `CONTENT_AUDIT` 真正分离：普通审核员可以审核内容，但没有事件重放权限，避免“能看事件”自然升级成“能重放事件”。

### 3. 为什么权限注解不能下沉到共享 Service

这是整个改造里最容易踩的坑。项目的审核、通知、Feed、热度、ES 校准和 Outbox 链路会在 RabbitMQ 消费者和定时任务线程中调用共享 Service，这些线程**没有 HTTP 请求的 SecurityContext**。如果把 `@PreAuthorize` 加到这些 Service 上，合法内部调用会抛 `AuthenticationCredentialsNotFoundException`，进而触发 Inbox 重试甚至进入 DEAD。

我的规则是：

- `@PreAuthorize` 默认只放在纯 HTTP Controller 或专用管理门面上；
- 被 MQ 消费者、定时任务、事件重放复用的内部 Service 禁止添加依赖当前用户权限的注解；
- 需要复用的能力拆成“带授权的 HTTP 门面 + 无 SecurityContext 依赖的内部应用服务”；
- 绝不通过手工向 `SecurityContextHolder` 塞入 SYSTEM_ADMIN 来绕过权限。

### 4. 角色变更如何即时生效

管理员角色变更后，权限不能等缓存过期才生效。我的做法是：角色数据以数据库为准，**高风险角色变更同时删除 Redis 登录态**，让旧 Token 立即返回 401，管理员必须重新登录。这比“等待 5 分钟缓存过期”更严格，也符合“权限收紧必须即时”的安全预期。

### 5. 优化后的好处

- 每个高风险操作都有独立权限点，风险边界清晰；
- 数据库是权限事实源，JWT 里的旧 `isAdmin` claim 不再被信任；
- 内容审核员无法封禁用户，事件只读用户无法重放事件；
- 权限注解不会打断 MQ 和定时任务，内部可靠链路不受影响。

---

## 六、优化五：JWT 只保留稳定声明，清理旧安全逻辑

### 1. 原来的问题在哪里

JWT 里写入了 `isAdmin` claim，等于把权限快照放进凭证里。管理员降权后，Token 里的 `isAdmin` 仍然是 1，任何读取该 claim 的判断都会让降权失效。同时旧的 `JwtTokenUserInterceptor`、`@RequireAuth` 体系与新链路重复，留着只会造成“两套鉴权规则不一致”。

### 2. 我的优化方式

- JWT 只保留稳定声明：`userId`、签发时间、过期时间；
- 删除 `UserController.login()` 中写入 `isAdmin` claim 的代码，并清理 `JwtClaimsConstant.IS_ADMIN`；
- 删除 `JwtTokenUserInterceptor`（职责已被 `OptionalJwtAuthenticationFilter` 接管）；
- 删除已失效的 `AuthInterceptor` 和 `@RequireAuth`，不留死代码；
- 保留数据库 `is_admin` 字段作为兼容，不参与任何权限判断，稳定后再讨论移除。

### 3. 优化后的好处

- 权限事实源唯一化：数据库 -> 角色 -> 权限，JWT 不再携带可被滥用的权限声明；
- 不存在两套鉴权链重复生效的问题；
- 降权、封禁后旧 Token 无法凭历史 claim 继续使用。

---

## 七、优化六：封禁后停止 WebSocket 实时推送

### 1. 原来的问题在哪里

封禁用户时会删除 Redis 登录态并写入封禁标记，这能阻止用户下一次 STOMP CONNECT，但**已经建立的连接不会被自动断开**，`NotificationConsumer` 仍然会把新通知推送给被封禁的用户。

### 2. 我的优化方式

我在 `NotificationConsumer` 推送前增加检查，但注意这里**不能**用 `BaseContext`、`@PreAuthorize` 或 `SecurityContext`，因为 MQ 消费者线程没有当前登录用户。我新增 `UserAccessStateService.canReceiveRealtimePush(userId)`：

```text
先查 Redis 封禁 Key
    ↓
存在 -> 不推送
不存在 -> 回源数据库 account_status
    ↓
明确为 0（正常）才推送，其他情况宁可不推送
```

关键点：

- Redis 异常不能认为用户正常，必须回源数据库兜底；
- 数据库查询异常时不推送、**但消息仍然 ACK**，不能让它进入 RETRY/DEAD；
- 数据库通知照常保存并完成 Inbox，封禁只影响实时体验，不影响通知的事实记录。

### 3. 为什么跳过推送不等于消费失败

跳过推送是产品语义（被封禁者不再实时接收），不是消息处理失败。如果把它当作失败处理，消息会无限重试冲击队列，还会让通知事实落库与 Inbox 状态互相矛盾。所以跳过推送只记录脱敏日志和指标，正常 ACK。

### 4. 优化后的好处

- 封禁用户不再收到新的实时通知，但数据库通知仍然可靠保存；
- 已建立连接在新通知到达时被跳过，不依赖“下一次重连失败”这种间接机制；
- MQ 可靠性链路不受影响，消息不会进入错误的重试路径。

---

## 八、优化七：管理员审计闭环

### 1. 原来的问题在哪里

审核、封禁、重放、角色修改都是高风险写操作，但原来只有普通日志，出了问题无法回答“谁在什么时候、通过哪个请求、对哪个对象、做了什么、前后发生了什么变化、成功还是失败”。

### 2. 我的优化方式

我新增 `admin_audit_log` 表，用 `@AdminAudit` 注解标记高风险 Controller 入口，由 `AdminAuditAspect` 负责采集通用信息（操作人、requestId、HTTP 方法、路径、客户端 IP、User-Agent、操作代码、目标类型和目标 ID、执行结果），业务层显式提供脱敏后的前后摘要。

第一批接入的操作：

| 操作 | action | 目标类型 |
| --- | --- | --- |
| 帖子/回答/评论审核 | `CONTENT_AUDIT` | `CONTENT` / `ANSWER` / `COMMENT` |
| 管理员删除内容 | `CONTENT_DELETE` | 对应类型 |
| 举报处理 | `REPORT_HANDLE` | `REPORT` |
| 身份审核 | `IDENTITY_AUDIT` | `IDENTITY_AUTH` |
| 封禁 / 解封 | `USER_BAN` / `USER_UNBAN` | `USER` |
| 事件重放 | `EVENT_REPLAY` | `OUTBOX` / `INBOX` |
| 角色授予 / 撤销 | `ROLE_GRANT` / `ROLE_REVOKE` | `USER_ROLE` |

### 3. 成功与失败为什么必须分开事务

这是审计最容易做错的地方：

- **成功审计**与业务操作在**同一个事务**（`Propagation.MANDATORY`），业务数据和成功审计一起提交、一起回滚。审计写入失败 -> 业务回滚，真正做到 fail-closed，不会出现“业务成功但审计丢失”或“业务回滚却留下成功审计”；
- **失败审计**使用 `Propagation.REQUIRES_NEW` **独立事务**。因为原业务事务已经回滚，失败痕迹必须留在独立事务里，而且失败审计自身失败只能记录告警，**绝不能覆盖原业务异常**。

### 4. 敏感信息如何脱敏

审计摘要禁止盲目序列化整个参数和实体。以下内容不进入审计表：JWT、Redis Token、微信 code、密钥和密码、完整身份证号和手机号、上传文件内容、超长正文和 payload。业务层只提供白名单摘要，例如 `accountStatus: 0 -> 1`、`PENDING -> APPROVED`、`DEAD -> PENDING`。

### 5. 查询接口

新增 `GET /admin/audit-logs/page` 和 `GET /admin/audit-logs/{id}`，都需要 `AUDIT_LOG_READ` 权限。支持按操作人、操作类型、目标类型、目标 ID、结果、时间范围查询，`pageSize` 最大 100，固定按时间倒序，不提供删除接口。审计日志表是管理写操作的强依赖，为 `operator_id`、`action`、`target_type`、`result_status` 等查询路径建立索引，避免分页全表扫描。

### 6. 优化后的好处

- 每个高风险操作成功和失败都有可查询记录；
- 业务回滚不会留下错误的成功审计；
- 用 requestId 可以串联一次操作的完整链路；
- 审计不包含敏感字段，满足合规要求。

---

## 九、优化八：Redis Lua 原子限流

### 1. 原来的问题在哪里

如果按照“先 `INCR` 再 `EXPIRE`”分两步执行，进程在两步之间宕机，Key 就永远不会过期，用户可能被永久限流。另外，限流逻辑散落在各接口里，无法统一返回 429 契约。

### 2. 我的优化方式

我使用 Redis Lua 脚本把“加一、首次设置过期时间、返回是否允许和剩余次数”在一个原子操作内完成：

```lua
local current = redis.call('INCR', KEYS[1])
if current == 1 then
    redis.call('EXPIRE', KEYS[1], ARGV[1])
end
local ttl = redis.call('TTL', KEYS[1])
local limit = tonumber(ARGV[2])
local allowed = 0
if current <= limit then allowed = 1 end
local remaining = limit - current
if remaining < 0 then remaining = 0 end
return {allowed, remaining, ttl}
```

通过 `@RateLimit` 注解 + AOP 接入业务入口：

| 场景 | 限流维度 | 初始阈值 |
| --- | --- | --- |
| 内容发布 | userId | 1 分钟 5 次 |
| 评论和回答发布 | userId | 1 分钟 15 次 |
| 文件上传 | userId + IP | 1 分钟 20 次 |
| 举报提交 | userId | 10 分钟 10 次 |
| 事件重放 | adminId | 5 分钟 3 次 |
| 封禁、角色修改 | adminId | 5 分钟 10 次 |

超过阈值统一抛出 `RateLimitExceededException`，在 `GlobalExceptionHandler` 中返回 HTTP 429 + `Result.code=429`，并设置 `Retry-After` 响应头，前端可以拿到等待秒数。

### 3. 为什么登录限流要考虑共享 IP

小程序的 `wx.request` 由用户设备直接请求后端，办公网、校园网、运营商 NAT 下大量用户可能共享同一个出口 IP。所以登录不能只按 IP 做严格限制。我的方案是两段式：调用微信接口前用宽松的 IP 哈希 + 应用全局阈值抵挡明显洪峰；换取 openid 后以 **openid 的 HMAC 摘要**做严格的账号维度限制。openid 不以明文进入限流 Key，防止低熵标识被离线枚举。

### 4. Redis 异常时的策略

- 事件重放、角色修改等高风险接口限流异常时 fail-closed；
- 匿名查询类接口限流 Redis 异常时暂时放行，但记录指标和告警。

### 5. 优化后的好处

- Lua 原子执行，不会出现永不过期的限流 Key；
- 并发请求不会突破允许数量；
- 429 契约统一，管理端和小程序都能正确提示“操作过于频繁”而不误清登录态。

---

## 十、优化九：管理端权限恢复与按钮级权限

### 1. 原来的问题在哪里

管理端之前靠登录时把 `isAdmin` 存进 `localStorage` 判断权限，这是“显示层猜测”，用户随便改浏览器存储就能看到管理菜单。而且管理员被降权后，前端不知道，菜单照样显示。

### 2. 我的优化方式

新增 `GET /user/security-context`，由后端权威返回当前用户的安全上下文（userId、roles、authorities、verified）。前端改造为：

- 登录后不再信任本地 `isAdmin`，而是调用 security-context 恢复权限；
- `router` 每个业务路由增加 `meta.authority`，`beforeEach` 在刷新页面时若未加载权限则先请求 security-context；无权限直接输入地址时跳转 `/403` 无权限页面，而不是一律跳回登录页；
- 菜单按 `canShowMenu` 动态显隐；
- 新增 `usePermission` composable，审核按钮 `CONTENT_AUDIT`、删除按钮 `CONTENT_DELETE`、封禁按钮 `USER_BAN`、重放按钮 `EVENT_REPLAY`、处理举报按钮 `REPORT_HANDLE`，全部按权限显隐；
- 用户管理列表去掉基于旧 `is_admin` 的筛选，详情抽屉调用 `/admin/roles/user/{userId}` 展示用户真实角色。

### 3. 为什么按钮隐藏不等于安全

前端按钮控制只是改善体验。即使前端不显示删除按钮，用户手工调用 API 仍然会被后端 `@PreAuthorize` 拦截返回 403。真正的安全边界永远在后端，前端只负责“把没有权限的东西藏起来”。

### 4. 优化后的好处

- 不同权限账号看到的菜单和按钮正确；
- 降权或强制下线后，刷新页面立即反映新权限；
- 直接输入无权限 URL 显示 403 页面，而不是被错误地踢回登录页。

---

## 十一、优化十：小程序请求层同步安全契约

### 1. 原来的问题在哪里

小程序 `request.ts` 能识别 HTTP 401 和 `Result.code=401`，但 HTTP 403、429 会先落入通用非 2xx 分支，被包装成“网络异常”，服务端的真实业务提示丢失，也不会区分“无权限”和“频率限制”。

### 2. 我的优化方式

同步后端契约，只改 TypeScript 源文件，通过项目现有构建流程生成 JavaScript：

- `ApiErrorType` 扩展 `forbidden`、`rateLimited`；
- HTTP 401 或 `code=401`：清理 Token 并进入登录流程；
- HTTP 403 或 `code=403`：不清理 Token，返回 `forbidden`，由页面提示无权限；
- HTTP 429 或 `code=429`：不清理 Token，返回 `rateLimited`，读取服务端 message 和 `Retry-After` 响应头，提示等待秒数；
- 其他非 2xx：优先读取标准 `Result` 的 message，再降级为网络错误文案。

### 3. 优化后的好处

- 两端对 401、403、429 的行为完全一致：401 才重新登录，403 保留会话提示无权限，429 保留会话提示限流；
- 429 能展示服务端给出的具体等待时间，而不是笼统的“网络异常”。

---

## 十二、测试与验证：我如何证明它不是纸面设计

### 1. 后端安全集成测试

- `SecurityFilterChainTests`：覆盖无 Token 访问强制登录接口返回 401、可选鉴权接口匿名与登录双场景、CORS、公开路径、管理接口授权等核心安全行为；
- `SwaggerAccessControlTests`：验证 API 文档按环境开关开放——生产语义（关闭）匿名访问返回 401，开发语义（开启）放行；
- 认证失败、权限不足、限流分别返回正确的 HTTP 状态与 `Result` JSON，没有被 `@RestControllerAdvice` 吞掉或混为一谈。

### 2. 后端编译与测试

- `mvn compile` 通过；
- `mvn test` 安全相关测试全部通过，且没有破坏原有可靠性测试；
- 删除旧 `JwtTokenUserInterceptor` 后，全项目搜索确认无任何残留引用，避免死代码和编译失败。

### 3. 管理端验证

- `pnpm build` 生产构建通过；
- 使用真实后端登录，管理端通过 `/user/security-context` 恢复权限，不同角色看到的菜单和按钮正确；
- 审计日志页面列表、筛选、分页、详情抽屉验证通过。

### 4. 小程序验证

- `npm run typecheck` 通过；
- request 层对 401、403、429 的识别逻辑与后端契约一致。

---

## 十三、这次改造体现的核心思考

### 1. 权限事实源必须唯一且可信

JWT 里的 `isAdmin` 是登录时刻的快照，数据库 `user_role` 是实时事实。凡是能直接改数据库的地方，都不应该让前端或 Token 参与最终裁决。权限收紧时，宁可删除登录态要求重新登录，也不能等缓存自然过期。

### 2. 安全注解要放在“有用户上下文”的地方

`@PreAuthorize` 依赖 SecurityContext，而 MQ 消费者和定时任务没有 HTTP 用户。权限注解一旦下沉到共享 Service，就会用“用户身份校验”打断“系统内部可信调用”。正确的做法是拆开 HTTP 管理门面和内部应用服务，业务层继续负责对象归属、状态机和幂等。

### 3. 认证入口可以不同，认证能力必须唯一

HTTP Filter 和 STOMP ChannelInterceptor 是两个合法入口，但共享同一个 Token 认证服务。这既保证两端行为一致，又没有为了“统一”而把消息强行塞进 Servlet Filter Chain。

### 4. 审计要能回答完整的问题

审计不是“记一条日志”，而是回答“谁、何时、通过哪个请求、对哪个对象、做了什么、前后变化、成败”。为此成功审计必须与业务同事务（fail-closed），失败审计必须独立事务（保留痕迹），敏感字段必须在业务层做白名单脱敏。

### 5. 安全契约要前后端一起定

401、403、429 的语义如果不能在前端正确区分，后端做得再对，用户也只会看到“网络异常”。本次把 HTTP 状态码、`Result.code`、`Retry-After` 响应头定义成一份契约，管理端和小程序端同步实现。

---

## 十四、面试时可以这样讲

### 1. 一分钟版本

> 我在项目里做了一次完整的安全治理改造。原来的鉴权逻辑分散在 HTTP 拦截器和 WebSocket 两处，管理员权限只有一个 `is_admin` 字段，无法区分内容审核、封禁、事件重放这些风险不同的操作。我先抽出 HTTP 和 WebSocket 共用的 Token 认证服务，再引入 Spring Security 统一 HTTP 鉴权链路，同时保留 Redis 会话状态支持退出登录、封禁和降权的即时失效。接着建立了 `user_role` 角色表和权限常量，`@PreAuthorize` 只放在 HTTP Controller 上，避免打断 MQ 和定时任务等无 SecurityContext 的内部调用。最后补上了高风险管理操作的审计闭环和 Redis Lua 原子限流，并让管理端通过 `/user/security-context` 恢复真实权限，小程序同步支持 401、403、429 的区分。整个过程每一步都先用测试锁定原有行为，再逐步替换。

### 2. 如果面试官问“最难的点是什么”

可以回答：

> 最难的不是把鉴权逻辑换成 Spring Security，而是想清楚“权限注解到底该放哪一层”。项目的审核、通知、Feed、热度、Outbox 链路会在 RabbitMQ 和定时任务线程里调用共享 Service，这些线程没有 HTTP 的 SecurityContext。如果把 `@PreAuthorize` 加到这些 Service，合法内部调用就会抛 `AuthenticationCredentialsNotFoundException`，进而触发重试甚至进入 DEAD。我通过调用图清单确认每个注解的调用方，拆出带授权的 HTTP 门面和无用户上下文的内部应用服务，并用空 SecurityContext 的回归测试把关。

### 3. 如果面试官问“为什么保留 Redis 而不是纯无状态 JWT”

可以回答：

> 纯 JWT 无法在服务端撤销已经签发的会话。退出登录、封禁、新登录顶掉旧会话、高风险降权这些场景都需要服务端有能力让旧 Token 立即失效。所以我的方案是 JWT 负责签名身份，Redis 负责当前有效会话，每次请求比对一次。代价是多一次 Redis 读取，但换来了有状态的会话撤销能力，对当前项目是合理的取舍。

### 4. 如果面试官问“审计为什么成功和失败要分开事务”

可以回答：

> 成功审计如果独立事务，业务提交后审计写入失败，就出现“业务成功但无审计”；反之业务回滚了，独立事务里的“成功审计”却已经写入，就变成假记录。所以成功审计必须和业务在同一个事务，fail-closed，审计失败整个业务回滚。失败审计则相反，业务事务已经回滚，失败痕迹必须用 `REQUIRES_NEW` 独立事务保留下来，而且失败审计自身失败不能覆盖原始业务异常。

### 5. 如果面试官问“这个方案还有什么不足”

可以回答：

> 第一版仍然是一个用户一个 Redis Key 的单会话模型，管理端和小程序同时在线会互相顶掉，后续可以改成带 clientType 的会话 Key。已经建立的 WebSocket 连接在封禁后是“新通知不再推送”，如果需要“立即断开连接”，还要增加 userId 到 session 的索引并主动关闭。限流阈值目前是配置化的初始值，还需要根据真实压测数据调整。数据库 `is_admin` 字段仍在做兼容，稳定运行后可以再讨论移除。这些边界我都记录在文档里，不会把计划中的能力说成已经完成。

---

## 十五、最终收益总结

| 维度 | 优化前 | 优化后 |
| --- | --- | --- |
| 认证入口 | HTTP 拦截器与 WebSocket 各写一份 | 共用 Token 认证服务，行为一致 |
| 会话撤销 | 退出登录、封禁能力分散 | JWT + Redis 混合模型，降权/封禁即时生效 |
| 权限模型 | 只有 `is_admin` 布尔判断 | 身份、角色、权限三层，按操作粒度授权 |
| 权限事实源 | JWT 里的 `isAdmin` 快照 | 数据库 `user_role`，JWT 只保留稳定声明 |
| 鉴权规则 | 存在未注册的 `@RequireAuth` 假死注解 | Spring Security 统一链路，注解只放有用户上下文处 |
| 安全异常 | 空 401、HTTP 200 + code 混用 | 401/403/429 统一 JSON 契约，两端同步 |
| 封禁推送 | 已建连接仍收实时通知 | 推送前检查状态，跳过但不破坏通知与 Inbox |
| 审计 | 只有普通日志，不可追溯 | 事务一致审计、脱敏摘要、fail-closed |
| 限流 | 无统一频率控制 | Redis Lua 原子限流 + 429 + Retry-After |
| 管理端权限 | 前端猜 localStorage | security-context 权威恢复 + 菜单/按钮/路由三级控制 |
| 小程序契约 | 403/429 被当网络异常 | forbidden / rateLimited 精确区分，保留会话 |

这次优化的价值不只是“引入了 Spring Security”，而是把一套只能区分登录与否的粗粒度鉴权，改造成了能够回答“谁、有什么角色、能不能做这件事、有没有被记录、有没有被限流”的完整安全链路，同时保持了 HTTP 与 WebSocket、后端与前端、人工入口与内部调用的清晰边界。
