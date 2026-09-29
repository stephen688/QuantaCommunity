# 按域分包模块化单体重构 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking. 当前环境没有这两个子技能时，按本计划三个阶段顺序在当前任务内执行；不得并行编辑同一批 Java 包声明、Mapper XML namespace 或测试 import。

**Goal:** 在不改变 HTTP、数据库、消息、缓存和业务语义的前提下，将 `demo0` 从全局按层分包重构为单 Maven 模块的 package-by-feature 模块化单体，并拆除现有大 Service、大 Mapper 和大 MQ 配置类。

**Architecture:** 顶层按 `content/answer/comment/interaction/feed/follow/user/identity/notification/moderation/search/rag/platform` 分域，域内继续使用项目原有的 `controller/service/service.impl/mapper/entity/dto/vo/enums/exception/config/properties/mq` 命名。跨域只调用对方 `service` 接口并传递 DTO、VO、枚举或 MQ 消息契约；禁止直接引用其他域的 Mapper、Entity 和 Service 实现。整体分三阶段：先迁小文件，再迁大多数中等文件并统一执行原测试，最后拆迁大类并收口架构门禁。

**Tech Stack:** Java、Spring Boot、Spring MVC、Spring Security、MyBatis、MySQL、Redis、RabbitMQ、Elasticsearch Java Client、Spring AI、JUnit 5、Mockito、Testcontainers、ArchUnit；具体版本以 `pom.xml` 为准。

**Spec:** `demo0/docs/后续demo0优化总方案.md` §12、`demo0/docs/主服务简历亮点与增强计划.md` §五，以及 2026-09-28 本轮 grill-me 已确认的决议；本计划中的“已确认决议”覆盖旧文档中不一致的包名和拆分草案。

## Global Constraints

- 仍是单 Maven 模块、单 Spring Boot 应用、单部署单元；不拆 Maven 子模块，不引入 Spring Modulith，不做微服务拆分。
- 本次是纯结构重构：HTTP 路径、请求/响应 JSON、HTTP 状态码、`Result.code`、数据库表结构、SQL 语义、Redis Key、MQ exchange/queue/routing key、Outbox/Inbox 状态机全部保持不变。
- 不做 DDD 战术模式，不引入聚合根、值对象、领域事件总线或 Repository 概念替换现有 Service/Mapper。
- 域内命名沿用原项目：`controller/user|admin|bot`、`service`、`service/impl`、`mapper`、`entity`、`dto`、`vo`、`enums`、`exception`、`config`、`properties`、`mq/consumer|producer|message`。包名统一小写；类名继续使用 DTO、VO、Service、ServiceImpl、Mapper 等现有后缀。
- 只有现有模式无法表达时才新增名字；允许新增的顶层业务域仅为已确认的 `interaction`、`feed` 和 `platform`。
- 实施分支固定为 `codex/package-by-feature-refactor`，从最新 `main` 创建；禁止在 `main` 或旧的 `feat/m5-breaker-cost-tier` 上实施。
- MyBatis Mapper XML 迁入 `resources/mapper/<domain>/` 子目录前，`mapper-locations` 固定改为 `classpath*:/mapper/**/*.xml`；实体分散后，`type-aliases-package` 固定改为 `com.quanta.demo0`。这两项必须进入第一笔提交。
- 跨域只允许依赖目标域的 `service` 接口、`dto`、`vo`、`enums` 和明确的 MQ 消息契约；禁止导入其他域的 `mapper`、`entity`、`service.impl`。
- Admin Controller 不拆。`AdminContentController`、`AdminCommentController`、`AdminAnswerController`、`AdminUserController` 等保持一个 Controller，通过注入多个细分 Service 完成查询和命令编排。
- Feed 中的用户兴趣画像固定命名为 `UserInterestProfileService` / `UserInterestProfileServiceImpl`；`UserProfileService` 名称只属于 `user` 域。
- `UserAccessStateService` 归 `platform/security/service`；实现不得继续直接依赖迁移后的 `user.mapper.UserMapper`，只能调用 `user.service.UserAccountService` 暴露的最小账号状态查询。
- `AuthenticatedUser` 放入 `platform/security/model`，`BaseContext` 放入 `platform/security/context`；不得创建 `platform/security/entity`。
- 原 `eventadmin` 概念统一归入 `platform/mq/admin`，不建立 `platform/eventadmin`。
- `interaction` 修改内容、评论、回答的赞/藏/评论计数时，使用同一本地事务内的同步 Service 调用；禁止为计数更新新增 MQ 事件或提交后异步更新。
- MySQL 仍是业务事实源。业务写入与 Outbox 插入继续同事务；只有既有异步副作用继续走 Outbox/Inbox。
- MQ Consumer、定时任务、Outbox Dispatcher 不得使用 `BaseContext` 或假设存在 `SecurityContext`。
- 内容详情缓存继续只缓存与访问者无关的稳定快照；点赞/收藏高亮和浏览历史逐请求处理。Feed 作者批量装配不得退化成 N+1。
- 保留当前工作树中的用户文件；只新增本计划文件，实施时只迁移 `demo0` 内本计划列出的源码、资源和测试，不覆盖 QuantaBot 或前端未提交内容。
- 禁止过度测试：纯包移动只做编译校验；大多数文件完成迁移后统一执行一次原有测试；大类拆分只跑受影响的定向测试；全部完成后再执行一次全量测试和一次既有 HTTP 回归。
- 不运行 JMeter、QuantaBot Persona、红队、付费模型调用或与本次结构重构无关的性能测试。

---

## 1. 已确认决议与现状核对

| 决议 | 结论 | 当前代码证据 | 计划落点 |
|---|---|---|---|
| Feed 画像改名 | 必改 | 当前存在全局 `UserProfileService`，同时未来 `user` 域也需要同名服务 | Task 5 改为 `feed.service.UserInterestProfileService` |
| `UserAccessStateService` 归安全平台 | 必改 | 当前位于 `com.quanta.demo0.service`，实现直接依赖 `UserMapper` | Task 7 迁至 `platform.security.service`，改调 `UserAccountService.getAccountStatus` |
| 安全模型和上下文分包 | 必改 | `AuthenticatedUser` 在 `security` 根，`BaseContext` 在 `entity` | Task 1 迁至 `security/model` 与 `security/context` |
| Event Admin 归消息管理 | 建议采纳 | 当前为 `AdminEventController/Service`，管理 Outbox/Inbox | Task 3 放入 `platform/mq/admin` |
| Admin Controller 不拆 | 已确认 | 现有每种对象各一个 Admin Controller | Tasks 5～7 保持 Controller 文件，只拆其注入 Service |
| 互动计数同步更新 | 已确认 | 当前赞藏写入后同步执行 `updateLiked/updateCollectCount/updateLikeCount/updateAnswerLikeCount` | Tasks 5～6 改为同步调用目标域 Counter Service，不新增消息 |
| MyBatis 递归扫描 | 必改 | 当前 `mapper/*.xml` 不会发现按域子目录，`type-aliases-package` 只扫描旧 entity 包 | Task 0 在第一笔提交改为递归 XML 和根包别名扫描 |

当前主要大文件规模，以实施前重新读取结果为准：

| 文件 | 当前规模 | 终态 |
|---|---:|---|
| `service/Impl/ContentServiceImpl.java` | 约 2007 行 | 拆入 content、interaction、feed、search |
| `service/Impl/CommentServiceImpl.java` | 约 1033 行 | 拆为评论命令、查询；互动移出 |
| `service/Impl/OutboxEventServiceImpl.java` | 约 786 行 | 通用 Outbox 存取与各域消息生产分离 |
| `config/RabbitMQConfig.java` | 约 702 行 | 共享 Rabbit 配置与各域队列声明分离 |
| `service/Impl/UserServiceImpl.java` | 约 616 行 | 拆为 user、identity、security 会话能力 |
| `service/Impl/FollowServiceImpl.java` | 约 564 行 | 关注关系与 Feed 投影分离 |
| `es/service/ElasticSearchServiceImpl.java` | 约 560 行 | 内容索引、回答索引、重建协调分离 |
| `service/Impl/AnswerServiceImpl.java` | 约 559 行 | 回答命令、查询；互动移出 |
| `mq/consumer/ModerationConsumer.java` | 约 431 行 | 消费适配与审核工作流分离 |
| `rag/vector/RagDocumentConverter.java` | 约 348 行 | 内容、回答、分块转换器分离 |

## 2. 终态包与文件职责

### 2.1 业务域

| 顶层域 | 保留/新增的内部包 | 关键文件 |
|---|---|---|
| `content` | `controller/user,admin,bot`、`service/impl`、`mapper`、`entity`、`dto`、`vo`、`exception`、`mq`、`properties` | `ContentController`、`AdminContentController`、`BotContentController`、`ContentCommandService`、`ContentQueryService`、`ContentCounterService`、`ContentAuditService`、`ContentTopicTagService`、`ContentDetailCacheService`、`BotContentSyncService`、对应 Impl 与 Mapper |
| `answer` | 同上 | `AnswerController`、`AdminAnswerController`、`AnswerCommandService`、`AnswerQueryService`、`AnswerCounterService`、`AnswerAuditService`、对应 Impl 与 Mapper |
| `comment` | 同上，另有 `policy` | `CommentController`、`AdminCommentController`、`BotCommentController`、`CommentCommandService`、`CommentQueryService`、`CommentCounterService`、`CommentAuditService`、`BotCommentService`、`CommentZonePolicy` |
| `interaction` | `controller/user`、`service/impl`、`mapper`、`entity`、`dto`、`vo`、`mq` | 内容/评论/回答互动 Service，赞藏举报和浏览历史 Mapper；现有 Controller 的 URL 不改变 |
| `feed` | `controller/user,bot`、`service/impl`、`mapper`、`entity`、`dto`、`mq`、`config`、`properties`、`utils` | `FeedQueryService`、`FollowFeedService`、`HotContentService`、`UserInterestProfileService`、`RecommendRerankService`、画像/Feed 消费者 |
| `follow` | `controller/user`、`service/impl`、`mapper`、`entity`、`dto`、`vo`、`exception` | `FollowCommandService`、`FollowQueryService`；不再拥有 Feed ZSET 和内容装配 |
| `user` | `controller/user,admin`、`service/impl`、`mapper`、`entity`、`dto`、`vo`、`exception`、`properties` | `UserAccountService`、`UserProfileService`、`AdminUserService`、缓存失效服务 |
| `identity` | `controller/user,admin`、`service/impl`、`mapper`、`entity`、`dto`、`vo`、`enums` | `IdentityService`、`IdentityExamService`、认证审核能力 |
| `notification` | `controller/user`、`service/impl`、`mapper`、`entity`、`vo`、`enums`、`mq` | 通知查询、已读、消费落库和推送编排 |
| `moderation` | `controller/admin`、`service/impl`、`mapper`、`entity`、`dto`、`vo`、`enums`、`client`、`policy`、`result`、`mq`、`config`、`properties` | 审核工作流、供应商客户端、审核结果分发；修正 `modertion` 拼写 |
| `search` | `controller/user`、`service/impl`、`mapper`、`entity`、`dto`、`vo`、`es`、`mq`、`config`、`properties`、`constant` | 搜索历史、热门发现、内容/回答索引、重建和对账 |
| `rag` | 保留已有 `generation/retrieval/vector/model/config`，新增标准 `controller/user`、`service`、`dto/vo` 仅在需要时 | RAG 编排和转换器不再直接依赖业务 Mapper |

### 2.2 平台层

| 包 | 关键文件 |
|---|---|
| `platform/security/model` | `AuthenticatedUser.java`、`AuthenticationSnapshot.java` |
| `platform/security/context` | `BaseContext.java` |
| `platform/security/service` | `TokenAuthenticationService.java`、`RateLimitService.java`、`SessionService.java`、`UserAccessStateService.java` |
| `platform/security/service/impl` | 对应实现；`UserAccessStateServiceImpl` 只调用 `user.service.UserAccountService` |
| `platform/security/filter|handler|annotation|aop|config|properties|constant|enums|exception|utils|vo` | 沿用现有安全类名迁入对应包 |
| `platform/mq/outbox|producer|service|service.impl|mapper|entity|enums|message|config|properties` | 通用 Outbox/Inbox、Rabbit 可靠发布和共享配置 |
| `platform/mq/admin/controller|service|service.impl|dto|vo` | `AdminEventController`、`AdminEventService` 及 Outbox/Inbox 管理模型 |
| `platform/audit` | 沿用 `controller/service/impl/mapper/entity/dto/annotation/aop/constant` |
| `platform/common/result|exception|constant` | `Result`、`PageResult`、`PageVO`、`ScrollResult`、真正通用异常和常量 |
| `platform/common/enums` | `AuditStatus.java`；内容、回答、评论和身份认证共同使用的既有审核状态码 |
| `platform/web/controller|handler|config` | `CommonController`、`GlobalExceptionHandler`、Web MVC、OpenAPI |
| `platform/websocket/config` | `WebSocketConfig` |
| `platform/oss/service|service.impl|config|properties` | OSS 访问封装；不保留全局 `utils.AliOssUtil` |
| `platform/redis/properties|utils` | `ReadPathCacheProperties`、`RedisTaskLockAdapter` |

## 3. 跨域 Service 契约

以下接口放在所属域的 `service` 包，其他域只依赖这些接口，不依赖实现和 Entity。

### 3.1 内容读取和计数

```java
public interface ContentQueryService {
    ContentVO getContentDetail(Long contentId);
    List<ContentSnapshotVO> getContentSnapshots(Collection<Long> contentIds);
    PageVO<ContentVO> getMyContentList(Long userId, Integer current, Integer size, AuditStatus auditStatus);
    PageVO<ContentVO> pageUserPublicContents(Long userId, Integer current, Integer size);
}

public interface ContentCounterService {
    void changeLikeCount(Long contentId, int delta);
    void changeCollectCount(Long contentId, int delta);
    void changeCommentCount(Long contentId, int delta);
}
```

`ContentSnapshotVO` 只包含 Feed/Search/RAG 需要的稳定字段，不包含当前访问者的 `isLiked`、`isCollected`，也不触发浏览历史。

### 3.2 回答、评论计数

```java
public interface AnswerCounterService {
    void changeLikeCount(Long answerId, int delta);
    void changeCommentCount(Long answerId, int delta);
}

public interface CommentCounterService {
    void changeLikeCount(Long commentId, int delta);
}
```

### 3.3 互动同步事务

互动 Mapper 继续沿用现有插入/删除两类方法，不引入隐含分支的万能 Mapper 方法：

```java
public interface ContentInteractionMapper {
    int insertContentLike(Long contentId, Long userId);
    int deleteContentLike(Long contentId, Long userId);
}
```

```java
@Transactional
public LikeResultVO likeContent(Long contentId, boolean targetLiked) {
    int rows = targetLiked
            ? contentInteractionMapper.insertContentLike(contentId, currentUserId())
            : contentInteractionMapper.deleteContentLike(contentId, currentUserId());
    if (rows > 0) {
        contentCounterService.changeLikeCount(contentId, targetLiked ? 1 : -1);
    }
    return buildLikeResult(contentId, targetLiked);
}
```

内容、评论和回答的计数更新规则固定如下：

1. 互动关系写入和目标计数更新处于同一个 Spring 本地事务；
2. 目标不存在、计数更新失败或唯一约束冲突时整个事务回滚；
3. 不创建 `LikeCountChangedMessage`、`CollectCountChangedMessage` 等新事件；
4. 现有用户行为画像事件仍可在事务内写 Outbox，但它只是派生画像信号，不能承担事实计数更新。

### 3.4 用户和安全

```java
public interface UserAccountService {
    UserLoginVO weChatLogin(UserLoginDTO userLoginDTO);
    Integer getAccountStatus(Long userId);
}

public interface UserProfileService {
    UserInfoVO getById(Long userId);
    void updateUserInfo(UserInfoDTO userInfoDTO);
    UserProfileVO getUserProfile(Long targetUserId, Long viewerId);
}

public interface UserInterestProfileService {
    Map<String, Double> getBehaviorProfile(Long userId);
    Map<String, Double> getExplicitProfile(Long userId);
    void applyBehaviorEvent(UserBehaviorMessage message);
    void reconcile(Long userId);
}
```

```java
package com.quanta.demo0.platform.security.service;

public interface UserAccessStateService {
    boolean canReceiveRealtimePush(Long userId);
}
```

`UserAccessStateServiceImpl` 保留“Redis 封禁快速判断，异常时数据库兜底，无法确认则拒绝实时推送”的现有语义；数据库状态通过 `UserAccountService.getAccountStatus` 获取。

### 3.5 审核结果回写

```java
public interface ContentAuditService {
    void approveContent(Long contentId);
    void rejectContent(Long contentId, String reason);
}

public interface AnswerAuditService {
    void approveAnswer(Long answerId);
    void rejectAnswer(Long answerId, String reason);
}

public interface CommentAuditService {
    void approveComment(Long commentId);
    void rejectComment(Long commentId, String reason);
}
```

`moderation` 只能调用这些 Service，不得导入三个业务域的 Mapper。

---

## Task 0: 对齐 main 基线并修正 MyBatis 扫描范围

**Files:**
- Modify: `src/main/resources/application.yml`
- Create: `docs/plans/2026-09-28-package-by-feature-modular-monolith.md`

**Interfaces:**
- Produces: `codex/package-by-feature-refactor` 与 `main` 同一基线；Mapper XML 子目录和分散 Entity 均可被 MyBatis 发现。

- [x] **Step 1: 从最新 main 创建实施分支**

```powershell
git switch -c codex/package-by-feature-refactor main
git rev-parse HEAD
git rev-parse main
```

Expected: 两个 SHA 相同；本轮实际基线为 `e3d33fa776f30a7db7f612dde7e14b5f2c0ee7f5`。

- [ ] **Step 2: 修改 MyBatis 两项扫描配置**

```yaml
mybatis:
  mapper-locations: classpath*:/mapper/**/*.xml
  type-aliases-package: com.quanta.demo0
```

不修改其他 MyBatis 参数，不移动 XML，不改变 SQL。

- [ ] **Step 3: 做一次静态检查和编译**

```powershell
rg -n "mapper-locations|type-aliases-package" src/main/resources/application.yml
mvn -DskipTests compile
```

Expected: 两项值与上方完全一致，编译退出码为 `0`。配置改动不新增独立测试。

- [ ] **Step 4: 创建第一笔精确提交**

```powershell
git add demo0/src/main/resources/application.yml demo0/docs/plans/2026-09-28-package-by-feature-modular-monolith.md
git commit -m "refactor: prepare feature package resource scanning"
```

不得暂存当前工作区其他未跟踪文件。

---

# 阶段一：迁移小文件和稳定平台类型

本阶段只移动低行为风险文件、修改 package/import/MyBatis namespace，不拆业务算法。每个 Task 完成后只执行编译；不逐文件重复运行测试。

### Task 1: 迁移公共结果、安全模型与请求上下文

**Files:**
- Move: `src/main/java/com/quanta/demo0/result/{Result,PageResult,PageVO,ScrollResult}.java` → `platform/common/result/`
- Move: `src/main/java/com/quanta/demo0/security/AuthenticatedUser.java` → `platform/security/model/AuthenticatedUser.java`
- Move: `src/main/java/com/quanta/demo0/security/AuthenticationSnapshot.java` → `platform/security/model/AuthenticationSnapshot.java`
- Move: `src/main/java/com/quanta/demo0/entity/BaseContext.java` → `platform/security/context/BaseContext.java`
- Move: `src/main/java/com/quanta/demo0/handler/GlobalExceptionHandler.java` → `platform/web/handler/GlobalExceptionHandler.java`
- Move: `src/main/java/com/quanta/demo0/controller/user/CommonController.java` → `platform/web/controller/CommonController.java`
- Move: `src/main/java/com/quanta/demo0/config/{WebMvcConfiguration,OpenAPIConfiguration}.java` → `platform/web/config/`
- Modify: every production/test import of the moved classes; do not rename JSON fields or Controller mappings.

**Interfaces:**
- Produces: stable common `Result/PageResult/PageVO/ScrollResult` imports and corrected `security.model` / `security.context` packages consumed by every later task.

- [x] **Step 1: Record the move-only baseline**

```powershell
git status --short
mvn -DskipTests compile
```

Expected: compile exits `0`; record unrelated working-tree files and do not stage them.

- [x] **Step 2: Move the listed files and update package/import declarations**

Use `git mv` from the repository root. Preserve class bodies except package/import changes.

- [x] **Step 3: Compile once for the complete move batch**

```powershell
mvn -DskipTests compile
```

Expected: `BUILD SUCCESS`; no test execution in this move-only task.

- [x] **Step 4: Commit the coherent package move**

```powershell
git add demo0/src/main/java demo0/src/test/java
git commit -m "refactor: move common results and security context"
```

### Task 2: 迁移业务域的小型 DTO、VO、Entity、Enum、Exception 和 Properties

**Files:**
- Move to `content/{dto,vo,entity,enums,exception,properties}`: `ContentDTO`、`ContentAdminQueryDTO`、`ContentAuditDTO`、`BotPolicyDocDTO`、`ContentVO`、`ContentDetailSnapshot`、`ContentDetailCacheEntry`、`BotPostVO`、`BotSyncDocVO`、`BotSyncPageVO`、`Content`、`ContentImage`、`BotPolicyDoc`、`ContentDetailState`、`ContentFailedException`、`ContentTopicProperties`.
- Move to `answer/{dto,vo,entity}`: `AnswerDTO`、`AnswerAdminQueryDTO`、`AnswerVO`、`QuestionAnswer`.
- Move to `comment/{dto,vo,entity,exception,policy}`: `CommentAddDTO`、`CommentPageDTO`、`ReplyPageDTO`、`CommentAdminQueryDTO`、`CommentAuditDTO`、`CommentPageVO`、所有 `BotComment*VO`、`ContentComment`、`CommentImage`、`ReplyCountRow`、`CommentFailedException`、`CommentZonePolicy`.
- Move to `interaction/{dto,vo,entity}`: `LikeStateDTO`、`CollectStateDTO`、内容/评论举报 DTO、`LikeResultVO`、`CollectResultVO`、`ContentLiked`、`ContentCollect`、`ContentReport`、`CommentLiked`、`CommentReport`、`AnswerLiked`、`BrowseHistory`.
- Move to `feed/{dto,entity,properties}`: `RecommendQueryDTO`、`FollowFeedQueryDTO`、`BotProfileEventDTO`、`UserProfileSignal`、`RecommendProperties`.
- Move to `follow/{dto,vo,entity,exception}`: `FollowStateDTO`、`FollowResultVO`、`Follow`、`FollowException`.
- Move to `user/{dto,vo,entity,exception,properties}`: 用户登录/资料/管理 DTO、用户相关 VO、`User`、用户异常、`WeChatProperties`; rename the joined projection `UserAuthInfo` to `user.vo.UserAuthInfoVO`.
- Move to `identity/{dto,vo,entity,enums}`: `UserAuthDTO`、`IdentityAuditDTO`、`IdentityExamDTO`、认证 VO、`UserAuth`、`UserAuthDisplayStatus`.
- Move to `notification/{vo,entity,enums}`: `NotificationVO`、`Notification`、`NotificationType`.
- Move to `moderation/{dto,vo,entity,enums,result,properties,policy}`: 审核 DTO/VO/Record、`ModerationDecision`、`ModerationTargetType`、`ModerationResult`、`AliyunModerationProperties`、`ModerationDisabledPolicy`.
- Move to `search/{dto,vo,entity,properties,constant}`: `SearchDTO`、搜索热门 VO、`EsPageResult`、`ReindexResult`、`SearchHistory`、`SearchTrendingProperties`、`EsIndexConstant`.
- Move to `platform/audit/{dto,entity}`: `AdminAuditLogQueryDTO`、`AdminAuditLog`.
- Move to `platform/mq/admin/{dto,vo}`: `InboxEventQueryDTO`、`OutboxEventQueryDTO`、`EventOverviewVO`、`EventRetryDistributionVO`、`EventStatusCountVO`.
- Move to `platform/mq/{entity,enums,properties}`: `InboxEvent`、`OutboxEvent`、`InboxAcquireResult`、`InboxEventStatus`、`OutboxEventStatus`、`OutboxEventType`、`OutboxDispatchProperties`、`OutboxMaintenanceProperties`.
- Move to `platform/common/{exception,enums}`: `BaseException`、`NoFoundException`、`AuditStatus`.
- Move to `platform/security/{exception,vo,properties}`: `AuthFailedException`、`RateLimitExceededException`、`SecurityContextVO`、`JwtProperties`、`SecurityProperties`、`QuantabotProperties`.
- Move to `platform/redis/properties`: `ReadPathCacheProperties`.
- Move to `platform/oss/properties`: `AliOssProperties`.
- Move to `rag/{properties,exception}`: `RagProperties`、`RagRetrieveException`.
- Modify: all production/test imports only; preserve Lombok、Jackson、Validation annotations and field names.

**Interfaces:**
- Produces: every data type has one owning domain; later Service/Mapper migration imports these owning packages.

Ownership notes:

- `UserAuthInfoVO` is a user-owned joined read projection. Feed、Content、Comment、Answer and RAG may temporarily import this VO until their dedicated snapshot boundaries land; they must never import it as a user Entity. `UserMapper.xml` result types and batch author queries change package/class only, not SQL or batching.
- `UserAuthStatusVO` belongs to `identity/vo` even while the legacy `UserController` temporarily returns it.
- `ContentAuditDTO` temporarily belongs to `content/dto`; Task 7 creates `AnswerAuditDTO` for the answer admin boundary with the same JSON fields.
- Bot content/comment VO files currently under `controller/bot/vo` move to their owning domain `vo` packages.
- `ModerationDecision`、`ModerationTargetType`、`ModerationDisabledPolicy` currently live under `annotation`; move them with their consumers and update `ModerationTaskMessage` in the same batch.

- [x] **Step 1: Move by domain, not by old technical package**

Move platform support types first, then content → answer → comment → interaction → follow/user/identity → feed/search → notification/moderation/rag. Complete one group’s package declarations and imports before the next. Do not rename fields or convert Entity to record; `UserAuthInfo` → `UserAuthInfoVO` is the single approved class rename in this task.

- [x] **Step 2: Compile once after all leaf models are moved**

```powershell
mvn -DskipTests compile
```

Expected: `BUILD SUCCESS`.

- [x] **Step 3: Check that old leaf packages are empty**

```powershell
rg --files src/main/java/com/quanta/demo0/dto src/main/java/com/quanta/demo0/vo src/main/java/com/quanta/demo0/entity src/main/java/com/quanta/demo0/enums
```

Expected: no remaining files except files explicitly deferred by a later task; empty directories are not committed.

- [x] **Step 4: Commit**

```powershell
git add demo0/src/main/java demo0/src/test/java
git commit -m "refactor: move domain data types into feature packages"
```

### Task 3: 迁移小 Mapper、XML、基础设施配置和管理审计

**Files:**
- Move Mapper interfaces and matching XML together: `FollowMapper`、`IdentityExamMapper`、`NotificationMapper`、`ModerationRecordMapper`、`SearchMapper`、`UserProfileSignalMapper`、`AdminAuditLogMapper`、`BotContentSyncMapper`.
- Move audit files to `platform/audit/{controller/admin,service,service/impl,mapper,entity,dto,annotation,aop,constant}`.
- Move `AdminEventController`、`AdminEventService`、`AdminEventServiceImpl`、Outbox/Inbox query DTO and event VO to `platform/mq/admin/...`.
- Move OSS classes to `platform/oss/{service,service/impl,config,properties}` while preserving the current upload behavior and configuration keys.
- Move WebSocket configuration to `platform/websocket/config/WebSocketConfig.java`.
- Modify: Mapper XML `namespace` and result type references; `application.yml` only if a fully qualified class name actually appears.

**Interfaces:**
- Produces: exact Mapper interface/XML namespace pairs in their owning domains; `platform/mq/admin` is the only event-admin location.

- [x] **Step 1: Move each Mapper and XML as one unit**

For every moved Mapper, update both locations before compiling:

```xml
<mapper namespace="com.quanta.demo0.notification.mapper.NotificationMapper">
```

- [x] **Step 2: Move audit and message-admin classes without changing endpoints**

Keep all `@RequestMapping` values and Admin authorization annotations unchanged. Admin Controller files remain whole.

- [x] **Step 3: Compile once**

```powershell
mvn -DskipTests compile
```

Expected: `BUILD SUCCESS`.

- [x] **Step 4: Commit**

```powershell
git add demo0/src/main demo0/src/test
git commit -m "refactor: move supporting mappers and platform administration"
```

---

# 阶段二：迁移大多数现有类并执行一次统一回归

本阶段迁移不需要拆分的大多数 Controller、Service、MQ、配置和工具类。阶段末只执行一次原有全量测试，不在每个包移动后重复测试。

### Task 4: 迁移不需要拆分的 Controller、Service、MQ 和配置

**Files:**
- Move unchanged Controllers into owning domains while preserving class names and mappings: all existing user/admin/bot Controllers except methods later从 `UserController` 分离到 `IdentityController`.
- Move small/medium Services and Impl into owning domains: cache服务、审核状态服务、Topic Tag、Bot同步、通知、身份考试、显式偏好、推荐重排、趋势缓存、用户缓存失效、搜索历史等不在“大文件清单”中的类.
- Move MQ consumer/producer/message to content、comment、feed、notification、moderation、search 或 `platform/mq` owning packages.
- Move `HotScoreCalculator` to `feed/utils`、`SensitiveWordChecker` to `moderation/utils`、`JwtUtil` to `platform/security/utils`.
- Move `Aliyun*ModerationClient` to `moderation/client` and fix `modertion` spelling.
- Move RAG existing files under `rag` without renaming generation/retrieval/vector/model subpackages.
- Defer only these large files to Tasks 5～9: `ContentServiceImpl`、`CommentServiceImpl`、`OutboxEventServiceImpl`、`RabbitMQConfig`、`UserServiceImpl`、`FollowServiceImpl`、`ElasticSearchServiceImpl`、`AnswerServiceImpl`、`ModerationConsumer`、`RagDocumentConverter` and their giant Mapper/XML splits.

**Interfaces:**
- Consumes: domain-owned DTO/VO/Entity and Mapper packages from Tasks 1～3.
- Produces: most production code under final feature packages while retaining old large Service facades temporarily.

- [x] **Step 1: Move Controller files without splitting Admin Controllers**

An Admin Controller may inject multiple services:

```java
@RequiredArgsConstructor
public class AdminContentController {
    private final AdminContentQueryService adminContentQueryService;
    private final ContentGovernanceService contentGovernanceService;
}
```

Do not introduce `AdminContentQueryController` or `ContentGovernanceController`.

- [x] **Step 2: Move existing small Services and MQ adapters**

Only change package/import references. Keep listener queue names, concurrency, ACK mode, retry behavior and message JSON fields unchanged.

- [x] **Step 3: Move corresponding tests or update their packages**

Tests should mirror owning domains, for example:

```text
src/test/java/com/quanta/demo0/content/service/impl/ContentTopicTagServiceImplTest.java
src/test/java/com/quanta/demo0/feed/service/impl/RecommendRerankServiceImplTest.java
src/test/java/com/quanta/demo0/platform/security/TokenAuthenticationServiceImplTests.java
src/test/java/com/quanta/demo0/platform/mq/consumer/ConsumerReliabilityTests.java
```

- [x] **Step 4: Compile after the complete majority-migration batch**

```powershell
mvn -DskipTests compile
```

Expected: `BUILD SUCCESS`; old large classes may still exist under temporary legacy packages.

- [x] **Step 5: Commit**

```powershell
git add demo0/src/main demo0/src/test demo0/pom.xml
git commit -m "refactor: move services and adapters into feature packages"
```

### Task 5: 在拆大类前统一运行一次原有测试

**Files:**
- No production changes.
- Record actual evidence in this plan's execution record when implementation begins.

**Interfaces:**
- Consumes: majority-migrated application from Task 4.
- Produces: one clean behavioral baseline before large class decomposition.

- [x] **Step 1: Verify Docker once**

```powershell
docker info
```

Expected: server information is readable. If unavailable, mark Testcontainers tests `BLOCKED`; do not alter tests or assertions.

- [x] **Step 2: Run the pre-split full test suite once**

```powershell
mvn test
```

Expected: exit code `0`. This is the only full Maven run before large-class splitting.

- [x] **Step 3: Stop on behavioral regression**

Package/import errors are fixed within the migration commit. A real behavior failure is investigated before continuing; do not weaken assertions or skip existing tests.

---

# 阶段三：拆迁大文件并收口架构边界

大类拆分可能改变 Spring Bean 接线和事务边界，因此每组只执行与该组直接相关的定向测试。全部完成后统一执行最终门禁。

### Task 6: 拆分 ContentServiceImpl，并建立 interaction 与 feed 边界

**Files:**
- Create: `content/service/{ContentCommandService,ContentQueryService,ContentCounterService}.java`
- Create: `content/service/impl/{ContentCommandServiceImpl,ContentQueryServiceImpl,ContentCounterServiceImpl}.java`
- Create: `content/vo/ContentSnapshotVO.java`
- Create: `interaction/service/{ContentInteractionService,BrowseHistoryService,ReportGovernanceService}.java`
- Create corresponding `interaction/service/impl/*.java`.
- Create: `interaction/mapper/ContentInteractionMapper.java` and matching XML.
- Create: `feed/service/{FeedQueryService,FollowFeedService,HotContentService,UserInterestProfileService}.java`
- Create corresponding `feed/service/impl/*.java`.
- Rename: existing `UserProfileService` / `UserProfileServiceImpl` → `UserInterestProfileService` / `UserInterestProfileServiceImpl` under `feed`.
- Split: `service/Impl/ContentServiceImpl.java`、`service/Impl/FollowServiceImpl.java`、`mapper/ContentMapper.java`、`resources/mapper/ContentMapper.xml`.
- Modify without splitting: `ContentController`、`FollowController`、`AdminContentController`; inject the new services while preserving endpoints.
- Test: existing content cache、行为事件、推荐、热榜、关注作者缓存 tests; add one focused synchronous-count transaction test.

**Interfaces:**
- Produces: signatures in §3.1 and §3.3; `UserInterestProfileService` is the only Feed profile service name.
- Consumes: `FollowQueryService`、`UserProfileService`、Outbox service、cache services.

- [x] **Step 1: Write one failing test for the new synchronous count boundary**

```java
@Test
void likeContentUpdatesRelationAndCountInTheSameTransaction() {
    contentInteractionService.likeContent(10L, true);

    verify(contentInteractionMapper).insertContentLike(10L, 7L);
    verify(contentCounterService).changeLikeCount(10L, 1);
}
```

Expected initial result: compilation fails because the split services do not exist.

现有 `UserBehaviorMessage` 属于画像派生事件，继续保留；本测试只要求事实计数同步更新，不把画像事件误当作计数事件删除。

- [x] **Step 2: Extract command/query/counter logic without changing method bodies unnecessarily**

Move publish/delete to `ContentCommandServiceImpl`; detail and list reads to `ContentQueryServiceImpl`; atomic count SQL to `ContentCounterServiceImpl`. Preserve transaction annotations on Spring-proxied public methods.

- [x] **Step 3: Move interaction logic and keep count updates synchronous**

Move like、collect、report、browse-history relation operations to `interaction`. Call `ContentCounterService` inside the same transaction. Keep current Outbox user-behavior publication as a separate derived side effect.

- [x] **Step 4: Move recommendation, hot score and follow-feed projection**

Move Feed/Redis/profile methods out of Content and Follow services. `FollowService` retains only relationship commands and queries. Feed reads content via `ContentQueryService.getContentSnapshots`, never `ContentMapper`.

- [x] **Step 5: Preserve cache visitor boundaries**

`ContentDetailCacheService` continues caching stable snapshots only. `ContentQueryServiceImpl.getContentDetail` still computes current visitor like/collect flags and records browse history on every successful request.

- [x] **Step 6: Run the affected tests once**

```powershell
mvn '-Dtest=ContentServiceImplBehaviorEventTest,ContentServiceImplDetailCacheTest,ContentServiceImplRecommendSceneTest,ContentServiceImplTrendingCacheTest,ContentDetailCacheWritePathTest,FollowServiceImplAuthorCacheTest,RecommendRerankServiceImplTest,RecommendRerankRedisIntegrationTests,TrendingCacheServiceImplTest' test
```

Expected: exit code `0`. Rename test classes only when their production subject no longer exists; preserve assertions.

Execution: the focused Content/Feed/Follow/Security/MQ batch ran 80 tests with 0 failures and 0 errors. The batch used the renamed split-service tests and did not repeat the full Maven suite.

- [x] **Step 7: Commit**

```powershell
git add demo0/src/main demo0/src/test
git commit -m "refactor: split content interaction and feed services"
```

### Task 7: 拆分 CommentServiceImpl 与 AnswerServiceImpl

**Files:**
- Create: `comment/service/{CommentCommandService,CommentQueryService,CommentCounterService}.java` and Impl files.
- Create: `answer/service/{AnswerCommandService,AnswerQueryService,AnswerCounterService}.java` and Impl files.
- Create: `interaction/service/{CommentInteractionService,AnswerInteractionService}.java` and Impl files.
- Split: `service/Impl/CommentServiceImpl.java`、`service/Impl/AnswerServiceImpl.java`、`mapper/CommentMapper.java`、`mapper/QuestionMapper.java` and their XML.
- Modify without splitting: `CommentController`、`AnswerController`、`AdminCommentController`、`AdminAnswerController`.
- Preserve: `BotCommentService`、审核调用、缓存失效、Outbox、Bot mention 和删除级联语义.
- Test: existing comment behavior/Bot moderation/Bot comment tests plus one focused answer/comment synchronous count test.

**Interfaces:**
- Produces: `AnswerCounterService` and `CommentCounterService` from §3.2.
- Consumes: content counter/query services、moderation service、interaction services、Outbox service.

- [x] **Step 1: Write focused failing tests for synchronous count updates**

```java
verify(commentCounterService).changeLikeCount(commentId, 1);
verify(answerCounterService).changeLikeCount(answerId, -1);
```

Expected initial result: compilation failure before the new services exist.

- [x] **Step 2: Split command and query methods**

Comment send/delete go to command service; comment/reply pages go to query service. Answer publish/accept/delete go to command service; answer list/detail go to query service.

- [x] **Step 3: Move like/report relations to interaction**

Keep interaction relation change and counter service call in the same transaction. Do not add MQ count events.

- [x] **Step 4: Split Mapper interfaces by owner**

Comment/Answer core Mapper keeps entity lifecycle SQL; interaction Mapper owns like/report relation SQL; Admin Mapper owns management read models. Preserve SQL text and parameter names unless the interface split requires a namespace update.

- [x] **Step 5: Run affected tests once**

```powershell
mvn '-Dtest=CommentServiceImplBehaviorEventTest,CommentServiceImplBotModerationTest,CommentAuditServiceImplBotMentionTest,BotCommentServiceImplTest,AdminContentServiceImplTrendingCacheTest' test
```

Expected: exit code `0`.

Execution: the focused Comment batch ran 36 tests with 0 failures and 0 errors, followed by the previously blocked Answer synchronous-count test (1 test, passed). No full-suite rerun was performed.

- [x] **Step 6: Commit**

```powershell
git add demo0/src/main demo0/src/test
git commit -m "refactor: split comment and answer services"
```

### Task 8: 拆分 UserServiceImpl，落实 identity 与 security 修正

**Files:**
- Create: `user/service/{UserAccountService,UserProfileService}.java` and Impl files.
- Create: `identity/service/IdentityService.java` and `identity/service/impl/IdentityServiceImpl.java`.
- Move: identity-related methods out of `UserServiceImpl`.
- Move: logout/session invalidation to `platform/security/service/SessionService` and Impl.
- Move: `UserAccessStateService` and Impl to `platform/security/service` and `platform/security/service/impl`.
- Modify: `UserAccessStateServiceImpl` to depend on `UserAccountService.getAccountStatus(Long)` instead of `UserMapper`.
- Modify: `UserController`; create `identity/controller/user/IdentityController.java` only for the existing identity endpoints, preserving their original paths.
- Modify without splitting: `AdminUserController`.
- Test: security authorization/authentication、notification consumer、user cache invalidation tests.

**Interfaces:**
- Produces: contracts in §3.4; `UserProfileService` exists only in `user`, while Feed uses `UserInterestProfileService`.
- Consumes: platform token/session/cache facilities.

- [x] **Step 1: Add a failing package/behavior test for UserAccessStateService**

```java
assertThat(UserAccessStateService.class.getPackageName())
        .isEqualTo("com.quanta.demo0.platform.security.service");
verify(userAccountService).getAccountStatus(userId);
verifyNoInteractions(userMapper);
```

Expected initial result: old package or missing interface causes failure.

- [x] **Step 2: Split account, profile, identity and session methods**

Preserve WeChat login sanitization, auth display reconciliation, cache invalidation, token invalidation and transaction annotations. Do not change response DTO/VO fields.

- [x] **Step 3: Move access-state security service**

Retain Redis banned-key check and fail-closed fallback. Replace direct Mapper dependency with `UserAccountService.getAccountStatus`.

- [x] **Step 4: Run affected tests once**

```powershell
mvn '-Dtest=TokenAuthenticationServiceImplTests,TokenAuthenticationServiceImplBotTokenTest,OptionalJwtAuthenticationFilterBearerTest,SecurityFilterChainTests,VerifiedUserMethodSecurityTests,UserReadCacheInvalidatorImplTest,UserReadCacheWritePathTest,ConsumerReliabilityTests' test
```

Expected: exit code `0`.

Execution: the focused user/identity/security batch ran 21 tests successfully; the full Maven suite was intentionally deferred to the final gate.

- [x] **Step 5: Commit**

```powershell
git add demo0/src/main demo0/src/test
git commit -m "refactor: split user identity and security services"
```

### Task 9: 拆分 Elasticsearch、RAG 转换器、审核消费者和可靠消息大类

**Files:**
- Replace `ElasticSearchService/Impl` with `search/service/{ContentSearchService,AnswerSearchService,SearchReindexService}.java` and corresponding Impl.
- Create `search/es/mapper/{ContentDocumentMapper,AnswerDocumentMapper}.java` and `search/es/query/ElasticsearchQueryFactory.java`.
- Split `rag/vector/RagDocumentConverter.java` into `ContentRagDocumentConverter`、`AnswerRagDocumentConverter`、`RagChunkDocumentConverter`.
- Split `ModerationConsumer` into thin listener plus `moderation/service/ModerationWorkflowService` and Impl; preserve Inbox success transaction and ACK/NACK behavior.
- Refactor `platform/mq/service/impl/OutboxEventServiceImpl` to generic Outbox persistence only; move business payload construction to each domain producer.
- Split `RabbitMQConfig` into shared `platform/mq/config/RabbitMQConfig` and domain configs: content topic、feed/profile、moderation、notification、search. Preserve broker declarations exactly.
- Test: ES、RAG、moderation、Outbox/Inbox route and reliability tests.

**Interfaces:**
- Produces: search services return search-owned VO/document types rather than content/answer Entity.
- Produces: moderation workflow calls `ContentAuditService`、`AnswerAuditService`、`CommentAuditService` only.
- Produces: generic Outbox service accepts serialized payload/event metadata and imports no business Entity.

- [ ] **Step 1: Write failing architecture-focused tests**

```java
assertThat(Arrays.stream(OutboxEventServiceImpl.class.getDeclaredMethods())
        .flatMap(method -> Arrays.stream(method.getParameterTypes())))
        .noneMatch(type -> type.getPackageName().startsWith("com.quanta.demo0.content.entity"));
```

Add one moderation test proving target update and Inbox success remain in the same workflow transaction.

- [ ] **Step 2: Split search by content/answer index**

Move current query bodies without changing ES index names, fields, analyzers, pagination or highlight behavior. `SearchReindexService` coordinates both index services.

- [ ] **Step 3: Split RAG converters**

Preserve chunk IDs, metadata names, text construction and vector synchronization behavior. RAG obtains stable DTO/VO through content/answer Service, never their Mapper.

- [ ] **Step 4: Thin the moderation consumer**

Consumer performs message receipt and ACK/NACK only. `ModerationWorkflowService` owns Inbox lease, provider call, record save, result dispatch and success marking.

- [ ] **Step 5: Separate Outbox mechanics from business events**

Keep current transaction joining behavior. Domain producer builds its message and calls the generic Outbox append operation; Outbox implementation cannot import business Entity.

- [ ] **Step 6: Split Rabbit configuration without changing broker topology**

Before and after the split, compare exchange、queue、binding、DLX、routing key and converter declarations. Only Java configuration ownership changes.

- [ ] **Step 7: Run affected tests once**

```powershell
mvn '-Dtest=ElasticSearchServiceImplTest,ConsumerReliabilityTests,ContentTopicTagConsumerTest,ProfileReconcileConsumerTest,UserBehaviorConsumerTest,OutboxRouteRegistryBotMentionTest,OutboxRouteRegistryUserBehaviorTest,OutboxTopicRegistrationTest,ReliabilityMySqlIntegrationTests,OutboxRabbitIntegrationTests' test
```

Expected: exit code `0`; if Docker is unavailable, integration tests are `BLOCKED` and unit tests still run.

- [ ] **Step 8: Commit**

```powershell
git add demo0/src/main demo0/src/test demo0/src/main/resources
git commit -m "refactor: split search moderation and reliable messaging"
```

### Task 10: 加入 ArchUnit 门禁并删除旧顶层包

**Files:**
- Modify: `pom.xml` — add ArchUnit JUnit 5 test dependency only; no runtime dependency.
- Create: `src/test/java/com/quanta/demo0/architecture/PackageArchitectureTest.java`.
- Delete after references reach zero: old top-level `controller`、`service`、`mapper`、`entity`、`dto`、`vo`、`enums`、`es`、`mq`、`modertion`、`policy`、`properties`、`result`、`security`、`annotation`、`aop`、`handler`、`utils` directories.
- Modify: `AGENTS.md` directory skeleton and package-boundary rules.
- Modify: `docs/后续demo0优化总方案.md` only after implementation evidence exists.

**Interfaces:**
- Consumes: final package structure from Tasks 1～9.
- Produces: CI-verifiable architectural constraints.

- [ ] **Step 1: Add ArchUnit dependency and a failing boundary test**

```java
@AnalyzeClasses(packages = "com.quanta.demo0")
class PackageArchitectureTest {

    @ArchTest
    static final ArchRule noCrossDomainMapperAccess = noClasses()
            .that().resideOutsideOfPackages("..content..")
            .should().dependOnClassesThat().resideInAPackage("..content.mapper..");

    @ArchTest
    static final ArchRule noLegacyTopLevelPackages = noClasses()
            .should().resideInAnyPackage(
                    "com.quanta.demo0.controller..",
                    "com.quanta.demo0.service..",
                    "com.quanta.demo0.mapper..",
                    "com.quanta.demo0.entity..",
                    "com.quanta.demo0.dto..",
                    "com.quanta.demo0.vo..");
}
```

Expand the Mapper rule for every business domain and add rules that forbid cross-domain `service.impl` and `entity` access. Explicitly allow cross-domain `service`、`dto`、`vo`、`enums` and message packages.

- [ ] **Step 2: Run the architecture test and remove remaining violations**

```powershell
mvn '-Dtest=PackageArchitectureTest' test
```

Expected: initial run reports remaining legacy references; after import/package cleanup, exit code `0`.

- [ ] **Step 3: Verify old top-level packages contain no Java files**

```powershell
rg --files src/main/java/com/quanta/demo0/controller src/main/java/com/quanta/demo0/service src/main/java/com/quanta/demo0/mapper src/main/java/com/quanta/demo0/entity src/main/java/com/quanta/demo0/dto src/main/java/com/quanta/demo0/vo src/main/java/com/quanta/demo0/mq src/main/java/com/quanta/demo0/modertion
```

Expected: no results.

- [ ] **Step 4: Commit**

```powershell
git add demo0/pom.xml demo0/src demo0/AGENTS.md demo0/docs
git commit -m "test: enforce feature package boundaries"
```

### Task 11: 最终验证和文档收口

**Files:**
- Modify: `docs/api-test/RESULTS.md` with actual final evidence only.
- Modify: `docs/后续demo0优化总方案.md` item 12 status only after all required evidence passes.
- Modify: this plan's execution record and checkboxes during implementation.

**Interfaces:**
- Consumes: complete module structure and ArchUnit rules.
- Produces: one final regression result and one HTTP compatibility result; no performance claims.

- [ ] **Step 1: Run the final full Maven suite once**

```powershell
docker info
mvn test
```

Expected: `BUILD SUCCESS`. Record test count, exit code and any environment-limited tests.

- [ ] **Step 2: Start or reuse the verified local application and run one existing HTTP regression**

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\docs\api-test\scripts\run-phase.ps1 -Phase all
```

Expected: existing public endpoints retain their status codes and response contracts. If MySQL、Redis、RabbitMQ、ES、OSS or AI dependencies are unavailable, mark the affected phase `BLOCKED` rather than changing code or tests.

- [ ] **Step 3: Run static closure checks**

```powershell
rg -n "com\.quanta\.demo0\.(controller|service|mapper|entity|dto|vo|mq|modertion)\." src/main/java src/test/java src/main/resources
git diff --check
git status --short
```

Expected: no legacy package imports or Mapper XML namespaces; `git diff --check` exits `0`; unrelated user files remain untouched.

- [ ] **Step 4: Update documentation with verified facts**

Record:

- final package tree;
- split classes and their new owners;
- ArchUnit rule names;
- midpoint and final Maven results;
- HTTP regression result or exact `BLOCKED` dependency;
- no HTTP/DB/MQ/cache contract changes;
- no JMeter or performance claim.

- [ ] **Step 5: Final commit**

```powershell
git add demo0/docs demo0/AGENTS.md
git commit -m "docs: record modular monolith refactor evidence"
```

## 4. 测试预算与停止条件

为避免过度测试，本计划固定测试预算：

1. 阶段一 move-only Task：Task 1、Task 3 各最多一次编译；Task 2 允许在平台支持类型完成后和全部业务 leaf 完成后各编译一次，不跑测试。
2. 阶段二完成大多数迁移后：一次 `mvn test`。
3. 阶段三每个大类组：只运行该 Task 列出的定向测试一次；失败修复后只重跑失败集合。
4. ArchUnit：独立运行到通过一次。
5. 最终：一次 `mvn test`，一次现有 Phase all HTTP 回归。
6. 不重复运行已通过的 QuantaBot、Persona、红队、JMeter、浏览器或前端测试。

出现以下情况立即停止扩大验证范围并先修问题：

- HTTP 路径或 JSON 字段变化；
- MQ topology、消息字段、Outbox/Inbox 状态语义变化；
- interaction 计数变成异步；
- Feed 缓存了完整带访问者状态的 `ContentVO`；
- 跨域直接导入 Mapper、Entity 或 ServiceImpl；
- Admin Controller 被拆成多个新 Controller；
- `UserProfileService` 再次同时出现在 user 与 feed 两个域；
- 测试通过依赖删除、跳过或放宽原断言。

## 5. 自检清单

- [ ] 所有顶层域都使用原有 Controller/Service/Mapper/Entity/DTO/VO 命名模式。
- [ ] Feed 画像固定为 `UserInterestProfileService`。
- [ ] `UserAccessStateService` 位于 `platform/security/service`。
- [ ] `AuthenticatedUser` 位于 `platform/security/model`。
- [ ] `BaseContext` 位于 `platform/security/context`。
- [ ] Event Admin 位于 `platform/mq/admin`。
- [ ] Admin Controller 文件未拆分。
- [ ] 内容、评论、回答计数继续同步事务更新。
- [ ] 旧 MQ、缓存、审核、权限和 HTTP 契约未变。
- [ ] Mapper XML namespace 与新接口完全一致。
- [ ] ArchUnit 阻止跨域 Mapper、Entity、ServiceImpl 依赖和循环依赖。
- [ ] 中点只跑一次原测试，终点只跑一次全量和一次 HTTP 回归。

## 6. 执行记录

- 2026-09-28：完成 grill-me 设计讨论并生成本计划；本轮只写计划，尚未迁移业务代码、修改 `pom.xml` 或运行重构测试。
- 当前工作树已有用户未提交文件，实施时必须逐项保留；不自动 push、merge 或覆盖。
- 2026-09-28：Task 0 在 `codex/package-by-feature-refactor` 提交 `9692801`；MyBatis 递归 Mapper 扫描和根包类型别名配置编译通过。
- 2026-09-28：Task 1 提交 `dff2aec`；11 个公共/安全/Web 类迁包，`mvn -DskipTests compile`、`test-compile`、`git diff --check` 通过，独立审查无 Critical/Important/Minor。
- 2026-09-28：Task 2 平台支撑类型批提交 `e519684`；28 个 Audit/MQ/Common/Security/Properties/RAG 类型迁包，`mvn -DskipTests compile`、`git diff --check` 通过，独立审查无 Critical/Important/Minor。
- 2026-09-28：Task 2 的 Content/Answer/Comment 叶子模型批提交 `8b78ee1`；35 个数据模型迁包，`git diff --check` 通过，独立审查无 Critical/Important/Minor；按测试预算留待业务 leaf 全部迁移后统一编译。
- 2026-09-28：Task 2 的 Interaction/Follow/Feed 叶子模型批提交 `fe8b74f`；26 个数据模型迁包，`git diff --check` 通过，独立审查无 Critical/Important/Minor；未改变既有 UserBehavior/ProfileReconcile 消息契约。
- 2026-09-28：Task 2 的 User/Identity 叶子模型批提交 `b0659e4`；20 个数据模型迁包，联表投影重命名为 `UserAuthInfoVO`，Mapper 方法名、SQL 和批量查询语义保持不变；`git diff --check` 与独立审查通过。
- 2026-09-28：Task 2 最后一批 Notification/Moderation/Search 叶子模型提交 `9897900`；21 个类型迁包，旧顶层 `dto/vo/entity/enums` 包清空，统一 `mvn -DskipTests compile` 与 `git diff --check` 通过，独立审查无 Critical/Important/Minor。
- 2026-09-28：Task 3 提交 `b4d185a`；8 组 Mapper/XML 成对迁移，Audit、Event Admin、OSS、WebSocket 归位，`AliOssUtil` 收口为 `AliOssService`；最终增量编译、`git diff --check` 与独立审查通过。
- 2026-09-28：Task 4 Controller 子批提交 `e15b990`；19 个 Controller 整体迁入所属域，Admin Controller 未拆、`UserController` 身份方法暂留，`git diff --check` 与独立审查通过。
- 2026-09-28：Task 4 Service/支持类子批提交 `8fc37c7`；small/medium Service、缓存、审核、搜索、配置、工具和小型 Mapper/XML 迁入所属域，保留既定大类在 legacy 包，`git diff --check` 与独立审查通过。
- 2026-09-28：Task 4 MQ/适配器子批提交 `14f67f9`；MQ message/consumer/producer、Outbox 支撑类与 RAG 配置归位，对应测试同步迁移。首次编译仅暴露迁包漏 import，补齐后 `mvn -DskipTests compile` 对 367 个生产源码构建成功；`git diff --check` 与两轮独立审查通过，未改变队列、路由、ACK、重试或消息字段。
- 2026-09-28：Task 5 中点回归完成。Docker Server 29.6.1 可用；按预算仅执行一次全量 `mvn test`，424 个测试中 391 通过、1 跳过、33 个上下文错误，未出现业务断言失败。错误被定位为迁包后的旧 `target` class、三处测试 `DynamicPropertySource` 仍覆盖旧 Mapper 通配、测试专用 MapperScan 和读缓存 schema 漏列；未削弱断言或跳过测试。修复后 `Demo0ApplicationTests` 通过，原失败集合定向回归 32 个测试 0 failure/0 error/1 skipped，生产类型校正后的 `ReadPathCacheRuntimeIntegrationTests` 11/11 再次通过；提交 `9c491b0`，独立审查问题已闭环。
