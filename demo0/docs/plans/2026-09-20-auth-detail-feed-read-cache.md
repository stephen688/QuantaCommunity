# 认证、帖子详情与 Feed 作者读路径缓存 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在不改变 HTTP、WebSocket、权限和返回字段契约的前提下，为认证用户安全快照、帖子详情稳定快照和 Feed 作者信息建立与各自一致性要求匹配的 Caffeine/Redis 缓存，减少每次请求的重复 MySQL 读取。

**Architecture:** 三条链路共享“事务提交后失效”和“缓存失败回源”的原则，但不共享同一个大对象：认证缓存只保存安全判断所需快照并继续逐次校验 JWT、Redis 会话和封禁标记；帖子详情缓存只保存与访问者无关的内容/图片快照，点赞与收藏高亮、浏览历史仍逐次处理；作者缓存提供单个和批量读取，Feed 一次批量补齐所有 L1 未命中作者，避免退化为 N+1。帖子详情采用 Caffeine L1 + Redis L2，认证和作者信息采用短 TTL Caffeine，并由真实写路径在事务提交后驱逐当前实例。

**Tech Stack:** Java、Spring Boot、Caffeine、Spring Data Redis、MyBatis、JUnit 5、Mockito、Testcontainers；版本以 `pom.xml` 为准，JMeter 属于后续压测计划。

**Spec:** `docs/后续demo0优化总方案.md` §1；设计和验收边界以本计划第 2～6 节为准。

**状态核对（2026-09-28）：** 认证、详情、Feed 作者缓存及事务提交后失效代码已落地；非压测功能门禁为 `PASS`，缓存专项 132 项及全量 389 项回归通过，分层证据与明确限制见 [RESULTS.md 的 S-RC 小节](../api-test/RESULTS.md#read-path-cache)。用户已将压测移入总方案第 7 项，性能仍 `PENDING`；本项最终状态只衡量非压测功能门禁。下方原始复选框不作为当前验收结论，状态以本轮收口清单及 RESULTS 为准。

## 2026-09-28 收口范围（用户已授权执行）

本轮完成非压测门禁；auth/detail/feed 的基线、候选和性能指标统一转入总方案第 7 项「真实压测（JMeter）」，不作为本轮缓存功能完成的阻塞项。执行结果仍只归档到 `docs/api-test/RESULTS.md`。

- [x] 补 `ContentDetailCacheRedisIntegrationTests`：真实 Redis JSON/负结果往返、L2 回填 L1、TTL、墓碑、损坏 JSON、两种竞态顺序及再次失效；临时变异证明关键断言能失败。
- [x] 补 `ContentDetailCacheWritePathTest` 并完善用户写路径：发布/点赞/收藏/删除、管理员审核/删除、评论审核/删除/计数，用户资料/身份/角色/封禁的成功、失败、提交与回滚分支。现有接线正确，本轮没有生产行为修复。
- [x] 在隔离测试数据与依赖上执行 HTTP/STOMP 真栈认证、详情访问者隔离与浏览历史、Feed 作者装配、写后失效、Redis 故障恢复和多实例 TTL 边界，保留脱敏证据；不暂停用户共享 Redis。
- [x] Docker 检查通过后执行定向及全量 `mvn test`；归档实际命令、数量、退出码与局限，独立审查补强后收口。
- [x] 将性能步骤/指标迁移至总方案第 7 项的后续压测入口，更新本计划最终功能门禁、总方案状态与 RESULTS；不写未经执行的性能提升。

并行边界：Redis 集成、写路径测试与真栈验证各自拥有独立测试文件；所有 Maven 执行使用同一个本机互斥锁串行，避免覆盖共享 `target/`。生产文件修改按归属通知主 agent，文档由主 agent 统一维护。

## Global Constraints

- 默认工作目录为 `demo0/`；Git 命令在 `QuantaCommunity/` 仓库根目录执行。
- 严格按任务顺序实施；每个任务先写失败测试，再做最小实现，再运行指定验证。
- MySQL 是用户、身份、角色、帖子和计数的事实源；缓存只能保存可重建派生数据。
- HTTP 与 WebSocket 继续共用 `TokenAuthenticationService`；不得在 Filter 和 STOMP 拦截器各建一套缓存或认证逻辑。
- Redis 登录态与 `user:banned:{userId}` 仍在每次认证时检查；缓存命中不得放过退出登录、异地登录或封禁。
- `GET /content/detail/{contentId}` 的权限、异常语义、浏览历史、点赞/收藏高亮和 `ContentVO` 字段不变。
- `GET /follow/feed` 的游标、排序、过滤、点赞/收藏高亮和返回字段不变；本阶段只优化作者装配，不顺手缓存整页 Feed。
- 缓存序列化使用项目现有 `ObjectMapper`；配置通过 `@ConfigurationProperties`，不得散落 `@Value` 或环境变量读取。
- API 和压测执行结果只写入 `docs/api-test/RESULTS.md`；原始性能文件放入已忽略的 `perf/results/`。
- 当前工作树存在其他用户改动；实施和提交只暂存本计划列出的文件，不覆盖、不格式化无关文件。

---

## 1. 现状、收益与范围

### 1.1 认证链路

`OptionalJwtAuthenticationFilter` 的每个带 Token HTTP 请求和 `WebSocketConfig` 的每次 STOMP CONNECT 都调用 `TokenAuthenticationServiceImpl.authenticate()`。普通用户认证当前依次执行：

1. 解析 JWT；
2. 从 Redis 读取 `login:token:{userId}`；
3. 检查 Redis `user:banned:{userId}`；
4. `UserMapper.getById(userId)`；
5. 认证状态缓存未命中时 `UserMapper.getUserAuthByUserId(userId)`；
6. `UserRoleMapper.findRoleCodesByUserId(userId)`。

本阶段缓存第 4～6 步形成的安全快照；第 1～3 步每次执行。Service Token 与普通 Token 必须使用不同缓存 Key，防止配置的 Bot 用户通过普通 Token 误复用带 `BOT` 角色的快照。

### 1.2 帖子详情

`ContentServiceImpl.getContentDetail()` 当前读取帖子、作者、图片、当前用户点赞/收藏状态，并写浏览历史。不能缓存完整 `ContentVO`，原因是 `isLiked`、`isCollected` 属于访问者，浏览历史是每次请求的副作用；作者昵称/头像也有独立失效频率。

本阶段只缓存 `ContentDetailSnapshot`：帖子公开稳定字段、计数和图片 URL。作者通过作者缓存装配；点赞/收藏高亮与浏览历史仍逐次处理。帖子计数、审核状态或删除状态变化后，在事务提交后驱逐详情缓存。

### 1.3 Feed 作者装配

`FollowServiceImpl.getFollowFeed()` 当前已经使用 `selectUserAuthInfoByIds(userIds)` 做一页一次批量查询，并不是每条帖子一次作者 SQL。本阶段的真实收益是：相同作者跨页、跨用户、跨请求反复出现时，优先从 Caffeine 命中；一页中未命中的作者仍合并成一次批量 SQL，绝不退化为 N+1。

### 1.4 本阶段不做

- 不缓存 JWT 解析结果、原始 Token、Redis 登录态或封禁判断。
- 不把认证快照放 Redis；认证本来已经访问 Redis，会话安全优先，45 秒本地缓存只用于消除 MySQL 读取。
- 不缓存整页 Feed、Feed ZSET、当前用户点赞/收藏集合或浏览历史写入。
- 不改变内容发布、点赞、收藏、评论、审核、删除、Outbox/Inbox、MQ 和搜索索引的现有事务语义。
- 不做跨实例 Caffeine 广播；认证/作者的其他实例最多保留各自短 TTL，详情通过共享 Redis 墓碑收敛。
- 不在本阶段拆分 `ContentServiceImpl`、迁移包结构或引入 Spring Cache 抽象。

## 2. 不可改变的业务契约

### 2.1 认证

- 无 Token 公开请求仍保持匿名；无效/过期/被替换 Token 的现有失败原因不变。
- 普通用户每次认证必须校验 Redis 当前会话；Service Token 仍只允许配置的 Bot 用户并跳过普通会话校验。
- `user:banned:{userId}` 每次检查；封禁必须立即拒绝，不能等待 Caffeine TTL。
- 数据库用户不存在、数据库封禁、身份认证状态、管理角色和权限映射语义不变。
- 缓存 Loader 异常不得写入缓存；不得把数据库/Redis 故障转换成匿名成功。
- `AuthenticationCacheKey` 固定包含 `userId` 与 `serviceToken` 两个维度。

### 2.2 帖子详情

- 路径仍为 `GET /content/detail/{contentId}`，仍要求认证，响应仍为 `Result<ContentVO>`。
- `contentId == null`、不存在、已删除、未通过审核、发布者异常分别保留当前业务异常语义。
- 每次成功详情请求仍执行 `isContentLiked`、`isContentCollected` 和 `recordBrowseHistory`；三者都不得进入共享快照。
- `ContentDetailSnapshot` 固定包含：`contentId`、`contentType`、`title`、`content`、`publishUserId`、`auditStatus`、`createTime`、`liked`、`commentCount`、`collectCount`、`images`。
- L1 TTL 30 秒；L2 正常值基础 TTL 300 秒并加入正负 60 秒抖动；负结果 TTL 60 秒；失效墓碑 TTL 360 秒。
- Redis 失败时回源 MySQL，并允许把成功或负结果放入当前实例 L1；该次禁止回填 L2。
- 旧 Loader 不得在事务提交后的失效动作之后覆盖 L2；详情 L2 使用与热榜计划相同的“观察原值 + Lua compare-and-set + 唯一墓碑”语义。

### 2.3 作者信息

- `AuthorProfileCache.getAll()` 必须保持输入 ID 去重后的映射关系；未命中 ID 合并成一次 `selectUserAuthInfoByIds`。
- 缓存字段只包含 `UserAuthInfo` 当前对外装配需要的公开信息：用户 ID、昵称、头像、认证展示状态、账号状态、届数和部门。
- L1 TTL 180 秒，最大 10,000 条；查询不到的用户本阶段不做长期负缓存，避免删除/恢复后的额外陈旧窗口。
- 作者缓存失败或 Loader 失败时，不吞掉 MySQL 异常并伪造作者；沿用调用链现有异常行为。
- 昵称/头像、身份审核信息、封禁/解封变化后，当前实例在事务提交后驱逐作者条目；其他实例最多陈旧 180 秒，这是本阶段明确接受的边界。

## 3. 目标设计

### 3.1 认证读取链路

    TokenAuthenticationServiceImpl.authenticate(token)
      -> parseClaims + extractUserId
      -> 普通 Token 校验 login:token:{userId}
      -> 每次检查 user:banned:{userId}
      -> AuthenticationSnapshotCache.get(userId, serviceToken)
         -> Caffeine 命中：返回不可变安全快照
         -> 未命中：查询 User + UserAuth + UserRole，构建快照并写 L1
      -> 再次执行数据库封禁语义判断
      -> 每次新建 AuthenticatedUser，返回给 HTTP/WebSocket

缓存值不得直接复用可变的 `AuthenticatedUser` 实例。固定新增不可变值：

```java
public record AuthenticationSnapshot(
        Integer accountStatus,
        boolean verified,
        Set<String> roles,
        Set<String> authorities,
        boolean admin
) {}
```

缓存接口固定为：

```java
public interface AuthenticationSnapshotCache {
    AuthenticationSnapshot get(Long userId, boolean serviceToken);
    void evict(Long userId);
}
```

实现内部 Key 固定为 `record AuthenticationCacheKey(Long userId, boolean serviceToken)`；`roles` 和 `authorities` 写入前使用不可变副本。

### 3.2 作者读取链路

```java
public interface AuthorProfileCache {
    UserAuthInfo get(Long userId);
    Map<Long, UserAuthInfo> getAll(Collection<Long> userIds);
    void evict(Long userId);
}
```

`getAll()` 算法固定为：去除 null 并保持首次出现顺序；先逐个检查 L1；把全部未命中 ID 交给一次 `selectUserAuthInfoByIds`；将数据库返回值按 `userId` 写入 L1；合并命中和新加载值。不得对每个 miss 调 `selectUserAuthInfoById`。

### 3.3 帖子详情读取链路

```java
public enum ContentDetailState {
    FOUND, NOT_FOUND, DELETED, NOT_APPROVED, INVALID_AUTHOR
}

public record ContentDetailSnapshot(
        Long contentId,
        Integer contentType,
        String title,
        String content,
        Long publishUserId,
        Integer auditStatus,
        LocalDateTime createTime,
        Integer liked,
        Integer commentCount,
        Integer collectCount,
        List<String> images
) {}

public record ContentDetailCacheEntry(
        ContentDetailState state,
        ContentDetailSnapshot snapshot
) {}

public interface ContentDetailCacheService {
    ContentDetailCacheEntry getOrLoad(
            Long contentId,
            Supplier<ContentDetailCacheEntry> loader
    );
    void evict(Long contentId);
}
```

读取流程固定为：

    ContentServiceImpl.getContentDetail(contentId)
      -> ContentDetailCacheService.getOrLoad(contentId, loader::load)
         -> L1 命中：返回 entry
         -> L2 正常/负结果命中：回填 L1 并返回
         -> L2 未命中/墓碑/坏 JSON：保存 observedRaw，查询帖子与图片
         -> Lua 仅在 Redis 原值仍等于 observedRaw 时回填
      -> 根据 state 抛出现有业务异常，或取得 snapshot
      -> AuthorProfileCache.get(snapshot.publishUserId())
      -> 每次查询当前用户 isLiked / isCollected
      -> 每次写浏览历史
      -> 组装新的 ContentVO

Redis Key 固定为 `content:detail:{contentId}`。Value 是 `ContentDetailCacheEntry` JSON 或 `__INVALIDATED__:<UUID>` 墓碑。Lua 的比较与写入语义逐字复用热榜计划第 5 节；不得先 GET 比较后普通 SET。

### 3.4 写后失效

新增两个协调器：

```java
public interface UserReadCacheInvalidator {
    void evictAuthenticationAfterCommit(Long userId);
    void evictAuthorAfterCommit(Long userId);
    void evictAllAfterCommit(Long userId);
}

public interface ContentDetailCacheInvalidator {
    void evictAfterCommit(Long contentId, String reason);
}
```

存在真实事务时注册 `TransactionSynchronization.afterCommit()`；回滚不失效。无事务时立即失效。用户失效只清当前 JVM Caffeine；`evictAllAfterCommit` 还删除现有 `security:verified:{userId}`。详情失效先清当前 JVM L1，再以单条 Redis `SET key tombstone EX 360` 写唯一墓碑。

## 4. 固定配置

新增 `ReadPathCacheProperties`，前缀固定为 `quanta.cache.read-path`：

```yaml
quanta:
  cache:
    read-path:
      authentication:
        maximum-size: 10000
        ttl-seconds: 45
      author:
        maximum-size: 10000
        ttl-seconds: 180
      content-detail:
        l1-maximum-size: 10000
        l1-ttl-seconds: 30
        redis-ttl-seconds: 300
        redis-ttl-jitter-seconds: 60
        negative-ttl-seconds: 60
        tombstone-ttl-seconds: 360
```

配置校验必须保证所有 size/TTL 大于零，详情抖动非负且 `redisTtlSeconds - redisTtlJitterSeconds > 0`。

如果热榜计划已先完成且 `pom.xml` 已包含 Caffeine，本计划不得重复添加依赖；否则加入 `com.github.ben-manes.caffeine:caffeine`，版本交给 Spring Boot BOM。

## 5. 文件清单

新增：

- `src/main/java/com/quanta/demo0/properties/ReadPathCacheProperties.java`
- `src/main/java/com/quanta/demo0/security/AuthenticationSnapshot.java`
- `src/main/java/com/quanta/demo0/security/AuthenticationSnapshotCache.java`
- `src/main/java/com/quanta/demo0/security/AuthenticationSnapshotCacheImpl.java`
- `src/main/java/com/quanta/demo0/service/AuthorProfileCache.java`
- `src/main/java/com/quanta/demo0/service/Impl/AuthorProfileCacheImpl.java`
- `src/main/java/com/quanta/demo0/service/UserReadCacheInvalidator.java`
- `src/main/java/com/quanta/demo0/service/Impl/UserReadCacheInvalidatorImpl.java`
- `src/main/java/com/quanta/demo0/service/ContentDetailCacheService.java`
- `src/main/java/com/quanta/demo0/service/ContentDetailCacheInvalidator.java`
- `src/main/java/com/quanta/demo0/service/Impl/ContentDetailCacheServiceImpl.java`
- `src/main/java/com/quanta/demo0/service/Impl/ContentDetailCacheInvalidatorImpl.java`
- `src/main/java/com/quanta/demo0/service/Impl/ContentDetailDataLoader.java`
- `src/main/java/com/quanta/demo0/vo/ContentDetailCacheEntry.java`
- `src/main/java/com/quanta/demo0/vo/ContentDetailSnapshot.java`
- `src/main/java/com/quanta/demo0/enums/ContentDetailState.java`
- `src/test/java/com/quanta/demo0/security/AuthenticationSnapshotCacheImplTest.java`
- `src/test/java/com/quanta/demo0/properties/ReadPathCachePropertiesTest.java`
- `src/test/java/com/quanta/demo0/service/Impl/AuthorProfileCacheImplTest.java`
- `src/test/java/com/quanta/demo0/service/Impl/UserReadCacheInvalidatorImplTest.java`
- `src/test/java/com/quanta/demo0/service/Impl/ContentDetailCacheServiceImplTest.java`
- `src/test/java/com/quanta/demo0/service/Impl/ContentDetailCacheRedisIntegrationTests.java`
- `src/test/java/com/quanta/demo0/service/Impl/ContentDetailDataLoaderTest.java`
- `src/test/java/com/quanta/demo0/service/Impl/ContentServiceImplDetailCacheTest.java`
- `src/test/java/com/quanta/demo0/service/Impl/FollowServiceImplAuthorCacheTest.java`
- `src/test/java/com/quanta/demo0/service/Impl/UserReadCacheWritePathTest.java`
- `src/test/java/com/quanta/demo0/service/Impl/ContentDetailCacheWritePathTest.java`

修改：

- `pom.xml`（仅在 Caffeine 尚不存在时）
- `src/main/resources/application.yml`
- `src/main/java/com/quanta/demo0/constant/RedisConstants.java`
- `src/main/java/com/quanta/demo0/security/TokenAuthenticationServiceImpl.java`
- `src/main/java/com/quanta/demo0/security/VerifiedStatusCacheEvictor.java`（迁移调用后删除）
- `src/main/java/com/quanta/demo0/service/Impl/ContentServiceImpl.java`
- `src/main/java/com/quanta/demo0/service/Impl/FollowServiceImpl.java`
- `src/main/java/com/quanta/demo0/service/Impl/UserServiceImpl.java`
- `src/main/java/com/quanta/demo0/service/Impl/IdentityExamServiceImpl.java`
- `src/main/java/com/quanta/demo0/service/Impl/AdminUserServiceImpl.java`
- `src/main/java/com/quanta/demo0/service/Impl/AdminRoleServiceImpl.java`
- `src/main/java/com/quanta/demo0/service/Impl/AdminContentServiceImpl.java`
- `src/main/java/com/quanta/demo0/service/Impl/CommentAuditServiceImpl.java`
- `src/main/java/com/quanta/demo0/service/Impl/CommentServiceImpl.java`
- `src/main/java/com/quanta/demo0/service/Impl/AdminCommentServiceImpl.java`
- `src/test/java/com/quanta/demo0/security/TokenAuthenticationServiceImplTests.java`
- `src/test/java/com/quanta/demo0/security/TokenAuthenticationServiceImplBotTokenTest.java`
- `src/test/java/com/quanta/demo0/security/VerifiedStatusCacheEvictorTests.java`（由新失效器测试替代后删除）
- `docs/api-test/RESULTS.md`
- `docs/后续demo0优化总方案.md`

不创建新顶层 `cache` 包，不缓存 Entity 可变实例，不修改 Controller、DTO、公开 VO、Mapper SQL 返回字段或前端代码。

## 6. 正确性证据标准

性能脚本、负载协议、三轮基线/候选与指标比较已按用户 2026-09-28 指示转入 [后续压测计划（总方案第 7 项）](../后续demo0优化总方案.md#jmeter-load-testing)，本计划只验收缓存功能与一致性，不以性能数据阻塞功能收口。必须通过测试及真栈结果证明：

- auth 缓存热命中时仍读取登录态和封禁 Key，但不调用三个 MySQL Loader；
- detail 热命中时不查帖子和图片，但仍查询访问者点赞/收藏并写浏览历史；
- feed 一页作者全命中时不调用作者 Mapper，部分命中时仅一次批量 Mapper；
- 不得把详情 P99 改善描述成“详情完全不访问数据库”，因为浏览历史和用户态仍按契约执行；
- 不得把 Feed 作者优化描述成“20 次 SQL 降为 0”，基线当前就是一页一次批量作者 SQL。

## 7. 实施任务

每个 Task 的目标测试通过后再进入下一项，并只暂存该 Task 的 `Files` 清单。固定提交信息如下；Task 0、13、15 是检查/复核门，不单独提交，除非它们产生了实际修复：

| Task | Commit message |
|---|---|
| 1 | 已迁移至后续压测计划，本轮不执行 |
| 2 | `feat(cache): configure core read caches` |
| 3 | `feat(user): cache author profiles in batches` |
| 4 | `feat(security): cache authentication snapshots` |
| 5 | `refactor(security): use cached authentication snapshots` |
| 6 | `feat(content): add detail multilevel cache` |
| 7 | `refactor(content): compose details from cached snapshots` |
| 8 | `feat(user): invalidate read caches after commit` |
| 9 | `feat(user): evict caches on user state changes` |
| 10 | `refactor(feed): load authors through batch cache` |
| 11 | `feat(content): evict detail cache after writes` |
| 12 | `test(content): verify detail cache with redis` |
| 14 | `docs(cache): record read path verification` |

任何 Task 若尚未通过其目标测试，不得为了形成提交而跳过失败测试；Task 15 发现的问题使用与所属域一致的 `fix(...)` 提交，并在 RESULTS 中引用。

### Task 0：实施前确认与基线冻结

**Files:**
- Read: `AGENTS.md`
- Read: `docs/plans/2026-09-20-trending-multilevel-cache.md`
- Read: `docs/api-test/TEST_PLAN.md`
- Modify: `docs/api-test/RESULTS.md`

- [ ] 阅读上述文件，并核对热榜计划是否已实施；只复用已经存在且测试通过的 Caffeine/性能脚本能力，不假设计划中的类已经存在。
- [ ] 在仓库根目录执行 `git status --short`，把本任务开始前的用户改动记录到实施笔记；后续只暂存本计划文件。
- [ ] 运行 `mvn -DskipTests compile` 和现有认证定向测试，记录当前通过/失败/阻塞状态。
- [ ] 用有效 Token 各调用一次 `/user/security-context`、`/content/detail/{contentId}`、`/follow/feed?pageSize=20`，保存脱敏响应结构与日志，不保存 Token。
- [ ] 确认 JDK、Maven、Docker、MySQL、Redis 和应用端口；缺失项以 `BLOCKED` 记录，不修改测试伪装通过。JMeter 环境由后续压测计划检查。

验证：

```powershell
mvn -DskipTests compile
mvn -Dtest=TokenAuthenticationServiceImplTests,TokenAuthenticationServiceImplBotTokenTest,WebSocketAuthenticationTests test
```

### Task 1：性能基线（已迁移，本轮不执行）

执行文件、三个线程组、三轮协议与汇总标准统一见 [后续压测计划（总方案第 7 项）](../后续demo0优化总方案.md#jmeter-load-testing)。保留任务编号便于追踪原计划；不将迁移视为测试通过。

### Task 2：增加统一配置与 Caffeine 依赖

**Files:**
- Create: `src/main/java/com/quanta/demo0/properties/ReadPathCacheProperties.java`
- Modify: `src/main/resources/application.yml`
- Modify: `pom.xml`
- Test: `src/test/java/com/quanta/demo0/properties/ReadPathCachePropertiesTest.java`

- [ ] 先写配置绑定测试，断言第 4 节所有默认值，以及零/负 TTL、抖动导致非正下界时启动校验失败。
- [ ] 运行测试确认 RED；新增 `@ConfigurationProperties(prefix = "quanta.cache.read-path")` 与嵌套配置类。
- [ ] 仅在 `pom.xml` 没有 Caffeine 时添加依赖；不得添加第二种本地缓存库。
- [ ] 重跑配置测试和编译确认 GREEN。

### Task 3：实现批量优先的作者 L1 缓存

**Files:**
- Create: `src/main/java/com/quanta/demo0/service/AuthorProfileCache.java`
- Create: `src/main/java/com/quanta/demo0/service/Impl/AuthorProfileCacheImpl.java`
- Test: `src/test/java/com/quanta/demo0/service/Impl/AuthorProfileCacheImplTest.java`

**Interfaces:** Produces `get(Long)`、`getAll(Collection<Long>)`、`evict(Long)`，供 Task 6 和 Task 10 使用。

- [ ] 测试 `get()` 命中不查 Mapper、未命中查一次 `selectUserAuthInfoById` 并回填。
- [ ] 测试 `getAll()` 全命中零次 Mapper、部分命中只执行一次 `selectUserAuthInfoByIds`、重复/null ID 被安全去重。
- [ ] 测试批量查询缺少某个 ID 时不写假作者、不抛 `toMap` 重复键异常。
- [ ] 测试 `evict()` 后下一次请求重新加载；Loader 抛异常时不缓存失败。
- [ ] 运行测试确认 RED，实现最大 10,000、TTL 180 秒的 Caffeine 后确认 GREEN。

### Task 4：实现认证安全快照 L1 缓存

**Files:**
- Create: `src/main/java/com/quanta/demo0/security/AuthenticationSnapshot.java`
- Create: `src/main/java/com/quanta/demo0/security/AuthenticationSnapshotCache.java`
- Create: `src/main/java/com/quanta/demo0/security/AuthenticationSnapshotCacheImpl.java`
- Test: `src/test/java/com/quanta/demo0/security/AuthenticationSnapshotCacheImplTest.java`

**Interfaces:** Produces `get(userId, serviceToken)` 与 `evict(userId)`，供 Task 5 和 Task 7 使用。

- [ ] 测试首次加载分别查询 User、UserAuth、UserRole，第二次命中不再查询；未知用户与数据库异常不入缓存。
- [ ] 测试同一 userId 的普通 Token 与 Service Token 使用两个 Key；普通 Token 快照绝不能包含 `BOT`，合法 Service Token 快照包含 `BOT`。
- [ ] 测试 verified、管理角色、权限、SUPER_ADMIN 映射与现有 `RolePermissionMapping` 一致。
- [ ] 测试返回的角色/权限集合不可由调用方修改，`evict(userId)` 同时清理普通和 Service 两个 Key。
- [ ] 运行测试确认 RED，实现最大 10,000、TTL 45 秒的 Caffeine 后确认 GREEN。

### Task 5：让 HTTP 与 WebSocket 继续共用缓存后的认证服务

**Files:**
- Modify: `src/main/java/com/quanta/demo0/security/TokenAuthenticationServiceImpl.java`
- Modify: `src/test/java/com/quanta/demo0/security/TokenAuthenticationServiceImplTests.java`
- Modify: `src/test/java/com/quanta/demo0/security/TokenAuthenticationServiceImplBotTokenTest.java`
- Test: `src/test/java/com/quanta/demo0/config/WebSocketAuthenticationTests.java`

- [ ] 先调整测试：每次认证仍读取普通会话和 banned Key；安全快照命中时不直接调用 User/UserAuth/UserRole Mapper。
- [ ] 保留 JWT、Service Token 固定用户、会话不存在/不匹配、封禁、用户不存在等失败原因与判断顺序。
- [ ] 把数据库快照加载委托给 `AuthenticationSnapshotCache`；每次用快照新建 `AuthenticatedUser`，不把 Token 或可变认证对象写缓存。
- [ ] 运行全部认证和 WebSocket 测试，证明 Filter/ChannelInterceptor 不需要第二套改造。

### Task 6：实现详情数据 Loader 与 L1/L2 缓存

**Files:**
- Create: `src/main/java/com/quanta/demo0/enums/ContentDetailState.java`
- Create: `src/main/java/com/quanta/demo0/vo/ContentDetailSnapshot.java`
- Create: `src/main/java/com/quanta/demo0/vo/ContentDetailCacheEntry.java`
- Create: `src/main/java/com/quanta/demo0/service/ContentDetailCacheService.java`
- Create: `src/main/java/com/quanta/demo0/service/Impl/ContentDetailDataLoader.java`
- Create: `src/main/java/com/quanta/demo0/service/Impl/ContentDetailCacheServiceImpl.java`
- Modify: `src/main/java/com/quanta/demo0/constant/RedisConstants.java`
- Test: `src/test/java/com/quanta/demo0/service/Impl/ContentDetailDataLoaderTest.java`
- Test: `src/test/java/com/quanta/demo0/service/Impl/ContentDetailCacheServiceImplTest.java`

- [ ] Loader 测试覆盖 FOUND、NOT_FOUND、DELETED、NOT_APPROVED、INVALID_AUTHOR；FOUND 只查询一次图片并过滤空 URL。
- [ ] 缓存测试覆盖 L1 命中、L2 命中回填 L1、双层未命中、负结果 60 秒、坏 JSON 回源、Redis GET/SET 异常降级、正常 TTL 240～360 秒。
- [ ] 用两个 `CountDownLatch` 覆盖旧 Loader 与失效墓碑的两种排序，断言最终 Redis 不可能是旧 JSON。
- [ ] 增加墓碑未变化时允许新快照替换、墓碑再次变化时条件写失败的测试。
- [ ] 运行测试确认 RED；实现 Key `content:detail:{id}`、原子 Lua compare-and-set、唯一墓碑后确认 GREEN。

### Task 7：把帖子详情拆成共享快照与逐请求状态

**Files:**
- Modify: `src/main/java/com/quanta/demo0/service/Impl/ContentServiceImpl.java`
- Test: `src/test/java/com/quanta/demo0/service/Impl/ContentServiceImplDetailCacheTest.java`

- [ ] 测试稳定快照命中时不调用 `contentMapper.selectById` 和图片查询。
- [ ] 测试每次命中仍执行点赞、收藏和浏览历史逻辑；用户 A 的高亮不得泄漏给用户 B。
- [ ] 测试作者通过 `AuthorProfileCache.get()` 装配；发布者 ID 缺失仍抛“发布用户信息异常”，作者查询为空沿用改造前的空 `UserAuthInfo` 回退（已核对 `363302f^`，不改变历史接口行为）。
- [ ] 测试五种详情状态映射回当前异常语义，HTTP Controller 和 `ContentVO` 不改。
- [ ] 将现有详情专用组装从 `convertContentToVO()` 中拆出最小私有方法；其他列表调用暂不迁移，避免扩大范围。
- [ ] 运行目标测试，确认缓存只覆盖稳定字段，浏览历史写失败仍沿用现有 best-effort 日志行为。

### Task 8：实现事务提交后的用户缓存统一失效

**Files:**
- Create: `src/main/java/com/quanta/demo0/service/UserReadCacheInvalidator.java`
- Create: `src/main/java/com/quanta/demo0/service/Impl/UserReadCacheInvalidatorImpl.java`
- Delete after migration: `src/main/java/com/quanta/demo0/security/VerifiedStatusCacheEvictor.java`
- Test: `src/test/java/com/quanta/demo0/service/Impl/UserReadCacheInvalidatorImplTest.java`
- Delete after migration: `src/test/java/com/quanta/demo0/security/VerifiedStatusCacheEvictorTests.java`

- [ ] 测试无事务立即失效；事务中提交后恰好失效一次；事务回滚不失效。
- [ ] 测试认证失效清普通/Service 两个安全 Key；作者失效只清作者；全部失效还删除 `security:verified:{userId}`。
- [ ] Redis 删除失败只告警且不回滚已提交数据库；日志包含 userId/阶段，不包含 Token。
- [ ] 完成所有调用方迁移后再删除旧 `VerifiedStatusCacheEvictor`，确保不存在两个失效入口。

### Task 9：接入用户资料、身份、角色与封禁写路径

**Files:**
- Modify: `src/main/java/com/quanta/demo0/service/Impl/UserServiceImpl.java`
- Modify: `src/main/java/com/quanta/demo0/service/Impl/IdentityExamServiceImpl.java`
- Modify: `src/main/java/com/quanta/demo0/service/Impl/AdminRoleServiceImpl.java`
- Modify: `src/main/java/com/quanta/demo0/service/Impl/AdminUserServiceImpl.java`
- Test: `src/test/java/com/quanta/demo0/service/Impl/UserReadCacheWritePathTest.java`

- [ ] `updateUserInfo` 成功后驱逐作者；更新失败不驱逐。若方法当前无事务，保持数据库成功后立即失效，不擅自扩大事务。
- [ ] 微信登录同步确实修改昵称/头像时驱逐作者；未发生字段变化时不驱逐。
- [ ] 身份审核通过/驳回提交后执行 `evictAllAfterCommit`，覆盖 verified、作者届数/部门与安全快照；回滚不驱逐。
- [ ] 角色授予/撤销提交后驱逐认证快照，并保留现有撤销登录态语义；失败/回滚不驱逐。
- [ ] 封禁/解封提交后驱逐认证与作者缓存；封禁立即性仍由登录态和 banned Key 保证，不依赖 L1 驱逐成功。
- [ ] 运行测试确认所有成功、失败、无变化与回滚分支。

### Task 10：让 Feed 使用批量作者缓存

**Files:**
- Modify: `src/main/java/com/quanta/demo0/service/Impl/FollowServiceImpl.java`
- Test: `src/test/java/com/quanta/demo0/service/Impl/FollowServiceImplAuthorCacheTest.java`

- [ ] 测试一页作者全部命中时不调用 UserMapper；部分命中时 AuthorProfileCache 内部只发一次批量查询。
- [ ] 测试多个帖子属于同一作者时只装载一次，且返回列表的昵称/头像/届数/部门保持现有值。
- [ ] 测试缓存缺少已删除作者时沿用当前空 `UserAuthInfo` 回退，不改变 Feed 整页可用性。
- [ ] 只替换作者查询与 Map 构建段；Feed ZSET、帖子批量查询、类型过滤、游标、点赞/收藏和 VO 组装保持原样。
- [ ] 运行目标测试并检查 Mapper 调用次数，防止实现为逐作者 `get()` 导致 N+1。

### Task 11：建立详情事务提交后失效器

**Files:**
- Create: `src/main/java/com/quanta/demo0/service/ContentDetailCacheInvalidator.java`
- Create: `src/main/java/com/quanta/demo0/service/Impl/ContentDetailCacheInvalidatorImpl.java`
- Test: `src/test/java/com/quanta/demo0/service/Impl/ContentDetailCacheWritePathTest.java`

- [ ] 测试无事务立即清 L1 并写墓碑；事务提交后执行；回滚不执行。
- [ ] 测试 Redis 写墓碑失败只告警，L1 已清理，数据库事务结果不被伪装回滚。
- [ ] 在 `ContentServiceImpl` 的发布、点赞、收藏、用户删除成功路径登记失效；无权限、不存在、更新失败和回滚不失效。
- [ ] 在 `AdminContentServiceImpl` 的审核状态变化、管理员删除成功路径登记失效；审计与 Outbox 语义不变。
- [ ] 在 `CommentAuditServiceImpl` 的评论计数 +1/-1、`CommentServiceImpl` 的用户删除评论、`AdminCommentServiceImpl` 的管理员删除评论成功路径登记对应 contentId 失效。
- [ ] 对批量删除回复导致一次计数变化的路径只登记一次 contentId 失效，避免同一事务重复回调。
- [ ] 运行目标测试，证明点赞数、收藏数、评论数、审核与删除状态不会长期停留在详情缓存中。

### Task 12：使用真实 Redis 验证详情缓存

**Files:**
- Create: `src/test/java/com/quanta/demo0/service/Impl/ContentDetailCacheRedisIntegrationTests.java`

- [ ] 使用 Testcontainers `redis:7.2-alpine` 和动态端口，不写固定密码/端口。
- [ ] 覆盖正常与负 Entry JSON 往返、L2 命中回填新 L1、TTL 区间、墓碑 TTL、坏 JSON 恢复。
- [ ] 用真实 Redis 和 `CountDownLatch` 重现 stale-backfill；事务失效后的旧 Loader 不能覆盖墓碑，新 Loader 可在墓碑未变化时写入新值。
- [ ] Docker 不可用时保留测试并在 RESULTS 记 `BLOCKED`；不得改成 mock 冒充真实 Redis 通过。

验证：

```powershell
mvn -Dtest=ContentDetailCacheRedisIntegrationTests test
```

### Task 13：分层回归与故障演练

- [ ] 运行本计划全部目标测试，随后运行 `mvn test`；记录测试数、失败、错误、跳过和耗时。
- [ ] HTTP 连续调用与真实 Mapper 调用计数验证安全快照热命中，但退出登录、异地登录和封禁仍立即失败；生产 TTL 以配置为准。
- [ ] STOMP CONNECT 使用同一 Token 认证服务验证成功、过期 Token、封禁用户和 Service Token 边界。
- [ ] 详情连续调用验证 L1，以独立缓存实例验证真实 L2，再验证 MySQL 重建；每次仍有浏览历史和访问者高亮证据。过期收敛可用缩短 TTL 的隔离配置验证，不将其写成实际等待了默认 30 秒。
- [ ] 暂停 Redis：认证按现有安全策略失败或降级，不因缓存放行；详情回源 MySQL 并只写 L1；恢复后可重新建立 L2。
- [ ] 修改昵称/头像、审核身份、授予/撤销角色、封禁/解封，验证当前实例提交后失效；制造回滚验证缓存不变。
- [ ] 点赞、收藏、评论审核/删除、帖子审核/删除后读取详情，验证计数与可见性更新；模拟旧 Loader 并发确认墓碑阻止旧值回填。
- [ ] Feed 连续读取验证作者 L1 命中与部分命中批量装配；修改作者资料后当前实例立即更新，另一实例的配置 TTL 陈旧窗口通过缩短 TTL 的两实例测试验证，默认 180 秒仅作为当前配置边界写入 RESULTS。

命令：

```powershell
mvn -Dtest=ReadPathCachePropertiesTest,AuthenticationSnapshotCacheImplTest,TokenAuthenticationServiceImplTests,TokenAuthenticationServiceImplBotTokenTest,WebSocketAuthenticationTests,AuthorProfileCacheImplTest,UserReadCacheInvalidatorImplTest,ContentDetailDataLoaderTest,ContentDetailCacheServiceImplTest,ContentDetailCacheInvalidatorImplTest,ContentDetailCacheRedisIntegrationTests,ContentServiceImplDetailCacheTest,FollowServiceImplAuthorCacheTest,UserReadCacheWritePathTest,ContentDetailCacheWritePathTest,ReadPathCacheLocalExpiryTests,ReadPathCacheRuntimeIntegrationTests test
mvn test
```

### Task 14：归档功能验收并收口文档

**Files:**
- Modify: `docs/api-test/RESULTS.md`
- Modify: `docs/后续demo0优化总方案.md`

- [ ] RESULTS 写入真实 Redis、功能与故障矩阵、查询调用证据、定向/全量回归、变异抽查、独立审查和限制；压测单独引用后续计划，不宣称已通过。
- [ ] 更新总方案：热榜与本计划分别标记真实完成状态，只引用 RESULTS，不复制或编造性能数字。
- [ ] 运行 `git diff --check` 和 `git status --short`，确认 `perf/results/` 与用户无关改动未被纳入。

### Task 15：独立复核

- [ ] 复核认证缓存 Key 是否包含 Token 类型，普通用户是否可能获得 BOT/管理权限，封禁与会话撤销是否仍逐次生效。
- [ ] 复核详情快照是否完全排除 userId 相关状态，浏览历史是否每次执行，负缓存是否保持现有异常语义。
- [ ] 复核详情两种竞态顺序：回填先于墓碑、墓碑先于回填；最终 L2 均不能是旧 JSON。
- [ ] 复核 Feed 部分命中是否仍只发一次批量作者 SQL，没有隐藏 N+1。
- [ ] 复核所有用户/内容写路径的提交、回滚和失败分支；发现缺口先补失败测试再修复。
- [ ] 抽查本轮真实 Redis、HTTP/STOMP、写路径与回归原始报告，确认 RESULTS 与实际断言、命令结果一致；性能报告抽查转入后续压测计划。

## 8. 最终功能验收门禁（压测另项）

- HTTP、WebSocket、权限、Result、ContentVO、ScrollResult 和异常契约未变化。
- auth 热命中消除 User/UserAuth/UserRole MySQL 读取，但 JWT、会话、banned Key 仍逐次验证。
- Service Token 与普通 Token 缓存隔离，未出现 BOT/管理权限串用。
- detail 缓存不包含 `isLiked`、`isCollected` 或浏览者信息；每次请求仍处理用户态和浏览历史。
- detail 正常/负缓存、Redis 故障、坏 JSON、TTL、墓碑和 stale-backfill 均有测试；真实 Redis 集成测试通过，否则阶段不得标完整完成。
- Feed 作者全命中零 Mapper、部分命中一次批量 Mapper，游标和排序不变。
- 用户资料、身份、角色、封禁、帖子状态、点赞/收藏/评论计数的成功提交均有失效证据，回滚均不失效。
- 全量 `mvn test` 通过，或将无关既有失败单独记录为证据充分的 `PARTIAL/BLOCKED`。
- 压测任务已移入总方案第 7 项，功能结论不代表性能结论；不夸大详情“零数据库访问”或 Feed“20 次降为 0”。
- 原始性能产物、Token、密钥和用户无关改动未提交；独立复核无未解决高优先级问题。

## 9. 回滚与明确限制

本改造没有数据库迁移、消息格式或 API 变化。回滚时按提交逆序撤销写后失效接线、三条读路径编排、缓存实现和配置；删除 `content:detail:*` Redis Key 不影响事实数据，后续请求可从 MySQL 重建。

明确接受的限制：认证与作者 Caffeine 不做跨实例广播，其他实例分别最多陈旧 45 秒和 180 秒；详情共享 Redis 墓碑同样不广播到其他实例已命中的 L1，其他实例正常情况下仍有最多 30 秒的本地陈旧窗口。详情在 Redis 故障期间失效墓碑写入失败时，旧 L2 最多存活到剩余 TTL（上限 360 秒）；详情请求仍有浏览历史写入和用户态读取，因此缓存优化目标是降低稳定内容/图片/作者/认证查询，而不是把请求变成纯内存操作。TTL 为当前默认配置，修改后以配置为准。
