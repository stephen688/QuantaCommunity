# package-by-feature 最终包结构与文件清单

> 盘点日期：2026-09-29。本文只记录当前工作树的静态目录事实，不声称编译、测试、HTTP、依赖健康或运行时验证结果。本次仅枚举生产源码与 Mapper XML，未读取 secrets；未执行 Maven、git add 或 git commit。

## 1. 终态边界：12 个业务域 + platform

demo0 仍是一个 Spring Boot、单 Maven 模块的 package-by-feature 模块化单体。顶层共有 13 个域/平台包：12 个业务域（content、answer、comment、interaction、follow、feed、user、identity、notification、moderation、search、rag）和一个共享平台包 platform。生产 Java 清单实际为 448 个文件；其中根包启动类 1 个，13 个顶层包合计 447 个。Mapper XML 实际为 21 个，按所属域放在 resources/mapper 下。

| 顶层域/平台 | Java 文件数 | 新所有者概述 |
|---|---:|---|
| content | 48 | 内容、回答关联的内容主链路、内容缓存/同步与内容计数；AdminContentController 保持单文件编排。 |
| answer | 20 | 回答命令、查询、审核与计数；AdminAnswerController 保持单文件编排。 |
| comment | 35 | 评论命令、查询、审核、Bot 评论与计数；AdminCommentController 保持单文件编排。 |
| interaction | 34 | 赞、藏、举报、浏览历史及其 Mapper；互动关系写入与目标计数在同一本地事务中同步完成。 |
| follow | 10 | 关注关系命令与查询；关注流投影归 feed。 |
| feed | 44 | 关注流、推荐、热榜、曝光、画像与相关消息消费者；画像接口固定为 UserInterestProfileService。 |
| user | 29 | 账号、用户资料、用户查询及用户侧缓存；AdminUserController 保持单文件编排。 |
| identity | 18 | 身份认证申请、认证查询与认证审核能力。 |
| notification | 14 | 通知查询、消费落库、已读和通知消息。 |
| moderation | 27 | 审核工作流、供应商客户端、策略、结果与管理入口。 |
| search | 41 | 搜索历史、ES 文档/查询、索引、重建、对账与趋势缓存。 |
| rag | 28 | RAG 生成、检索、向量转换/同步与用户入口。 |
| platform | 99 | 公共结果/异常、审计、安全、Web/WebSocket、OSS、Redis、可靠 MQ/Outbox/Inbox 及 Event Admin。 |

| 根包文件 | Java 文件数 |
|---|---:|
| Demo0Application.java | 1 |

### 主要大类拆分后的所有者

- 大类拆分所有者：原 ContentServiceImpl 的内容能力落在 content，互动关系落在 interaction，Feed/推荐装配落在 feed，搜索索引与检索落在 search。
- 原 CommentServiceImpl 的评论能力落在 comment，评论赞/举报等关系落在 interaction；原 AnswerServiceImpl 的回答能力落在 answer，回答互动关系落在 interaction。
- 原 FollowServiceImpl 的关系能力落在 follow，Feed 投影落在 feed；原 UserServiceImpl 的账号/资料能力落在 user，身份认证及审核落在 identity，会话、访问状态与令牌能力落在 platform/security。
- 原 ElasticSearchServiceImpl 的内容/回答搜索、索引、重建和趋势能力由 search 持有；ModerationConsumer 的工作流由 moderation 持有，Inbox/Outbox 机械能力由 platform/mq 持有；RagDocumentConverter 的内容/回答/分块转换器由 rag/vector 持有。
- AdminController 不拆：当前 AdminContentController、AdminCommentController、AdminAnswerController、AdminUserController 等文件各自保留，通过多个领域 Service 编排。跨域只通过公开 Service、DTO、VO、枚举及明确消息契约，不直接依赖其他域 Mapper、Entity 或 ServiceImpl。

## 2. 生产 Java 文件清单（实际 448 个）

以下路径均相对 src/main/java/com/quanta/demo0/；包内保留实际 DTO、VO、Controller、Service、ServiceImpl、Mapper、Entity、消息、配置等原命名。

### 根包（1）

- Demo0Application.java


## answer（20 个）

### answer/controller/admin (1)

- answer/controller/admin/AdminAnswerController.java

### answer/controller/user (1)

- answer/controller/user/AnswerController.java

### answer/dto (2)

- answer/dto/AnswerAdminQueryDTO.java
- answer/dto/AnswerDTO.java

### answer/entity (1)

- answer/entity/QuestionAnswer.java

### answer/mapper (1)

- answer/mapper/QuestionMapper.java

### answer/mq/producer (1)

- answer/mq/producer/AnswerEventProducer.java

### answer/service (5)

- answer/service/AdminAnswerService.java
- answer/service/AnswerAuditService.java
- answer/service/AnswerCommandService.java
- answer/service/AnswerCounterService.java
- answer/service/AnswerQueryService.java

### answer/service/impl (5)

- answer/service/impl/AdminAnswerServiceImpl.java
- answer/service/impl/AnswerAuditServiceImpl.java
- answer/service/impl/AnswerCommandServiceImpl.java
- answer/service/impl/AnswerCounterServiceImpl.java
- answer/service/impl/AnswerQueryServiceImpl.java

### answer/vo (3)

- answer/vo/AnswerRagSnapshotVO.java
- answer/vo/AnswerSnapshotVO.java
- answer/vo/AnswerVO.java

## comment（35 个）

### comment/controller/admin (1)

- comment/controller/admin/AdminCommentController.java

### comment/controller/bot (1)

- comment/controller/bot/BotCommentController.java

### comment/controller/user (1)

- comment/controller/user/CommentController.java

### comment/dto (5)

- comment/dto/CommentAddDTO.java
- comment/dto/CommentAdminQueryDTO.java
- comment/dto/CommentAuditDTO.java
- comment/dto/CommentPageDTO.java
- comment/dto/ReplyPageDTO.java

### comment/entity (3)

- comment/entity/CommentImage.java
- comment/entity/ContentComment.java
- comment/entity/ReplyCountRow.java

### comment/exception (1)

- comment/exception/CommentFailedException.java

### comment/mapper (1)

- comment/mapper/CommentMapper.java

### comment/mq/message (1)

- comment/mq/message/BotMentionMessage.java

### comment/mq/producer (1)

- comment/mq/producer/CommentEventProducer.java

### comment/policy (1)

- comment/policy/CommentZonePolicy.java

### comment/service (6)

- comment/service/AdminCommentService.java
- comment/service/BotCommentService.java
- comment/service/CommentAuditService.java
- comment/service/CommentCommandService.java
- comment/service/CommentCounterService.java
- comment/service/CommentQueryService.java

### comment/service/bot (1)

- comment/service/bot/BotMentionDetector.java

### comment/service/impl (6)

- comment/service/impl/AdminCommentServiceImpl.java
- comment/service/impl/BotCommentServiceImpl.java
- comment/service/impl/CommentAuditServiceImpl.java
- comment/service/impl/CommentCommandServiceImpl.java
- comment/service/impl/CommentCounterServiceImpl.java
- comment/service/impl/CommentQueryServiceImpl.java

### comment/vo (6)

- comment/vo/BotCommentChainVO.java
- comment/vo/BotCommentHistoryVO.java
- comment/vo/BotCommentNodeVO.java
- comment/vo/BotCommentTreeVO.java
- comment/vo/CommentPageVO.java
- comment/vo/CommentSnapshotVO.java

## content（48 个）

### content/config (2)

- content/config/ContentMQConfig.java
- content/config/TopicTagMQConfig.java

### content/controller/admin (2)

- content/controller/admin/AdminContentController.java
- content/controller/admin/ContentTopicTagBackfillController.java

### content/controller/bot (1)

- content/controller/bot/BotContentController.java

### content/controller/user (1)

- content/controller/user/ContentController.java

### content/dto (4)

- content/dto/BotPolicyDocDTO.java
- content/dto/ContentAdminQueryDTO.java
- content/dto/ContentAuditDTO.java
- content/dto/ContentDTO.java

### content/entity (3)

- content/entity/BotPolicyDoc.java
- content/entity/Content.java
- content/entity/ContentImage.java

### content/enums (1)

- content/enums/ContentDetailState.java

### content/exception (1)

- content/exception/ContentFailedException.java

### content/mapper (2)

- content/mapper/BotContentSyncMapper.java
- content/mapper/ContentMapper.java

### content/mq/consumer (1)

- content/mq/consumer/ContentTopicTagConsumer.java

### content/mq/message (1)

- content/mq/message/ContentTopicTagMessage.java

### content/mq/producer (2)

- content/mq/producer/ContentEventProducer.java
- content/mq/producer/ContentTopicTagProducer.java

### content/properties (1)

- content/properties/ContentTopicProperties.java

### content/service (9)

- content/service/AdminContentService.java
- content/service/BotContentSyncService.java
- content/service/ContentAuditService.java
- content/service/ContentCommandService.java
- content/service/ContentCounterService.java
- content/service/ContentDetailCacheInvalidator.java
- content/service/ContentDetailCacheService.java
- content/service/ContentQueryService.java
- content/service/ContentTopicTagService.java

### content/service/impl (10)

- content/service/impl/AdminContentServiceImpl.java
- content/service/impl/BotContentSyncServiceImpl.java
- content/service/impl/ContentAuditServiceImpl.java
- content/service/impl/ContentCommandServiceImpl.java
- content/service/impl/ContentCounterServiceImpl.java
- content/service/impl/ContentDetailCacheInvalidatorImpl.java
- content/service/impl/ContentDetailCacheServiceImpl.java
- content/service/impl/ContentDetailDataLoader.java
- content/service/impl/ContentQueryServiceImpl.java
- content/service/impl/ContentTopicTagServiceImpl.java

### content/vo (7)

- content/vo/BotPostVO.java
- content/vo/BotSyncDocVO.java
- content/vo/BotSyncPageVO.java
- content/vo/ContentDetailCacheEntry.java
- content/vo/ContentDetailSnapshot.java
- content/vo/ContentSnapshotVO.java
- content/vo/ContentVO.java

## feed（44 个）

### feed/config (3)

- feed/config/FeedMQConfig.java
- feed/config/ProfileMQConfig.java
- feed/config/RecommendFeedInitializer.java

### feed/controller/bot (1)

- feed/controller/bot/BotProfileController.java

### feed/dto (3)

- feed/dto/BotProfileEventDTO.java
- feed/dto/FollowFeedQueryDTO.java
- feed/dto/RecommendQueryDTO.java

### feed/entity (1)

- feed/entity/UserProfileSignal.java

### feed/mapper (1)

- feed/mapper/UserProfileSignalMapper.java

### feed/mq/consumer (5)

- feed/mq/consumer/FeedDeleteConsumer.java
- feed/mq/consumer/FeedPushConsumer.java
- feed/mq/consumer/HotScoreUpdateConsumer.java
- feed/mq/consumer/ProfileReconcileConsumer.java
- feed/mq/consumer/UserBehaviorConsumer.java

### feed/mq/message (5)

- feed/mq/message/FeedDeleteMessage.java
- feed/mq/message/FeedPushMessage.java
- feed/mq/message/HotScoreMessage.java
- feed/mq/message/ProfileReconcileMessage.java
- feed/mq/message/UserBehaviorMessage.java

### feed/mq/producer (6)

- feed/mq/producer/FeedDeleteProducer.java
- feed/mq/producer/FeedEventProducer.java
- feed/mq/producer/FeedPushProducer.java
- feed/mq/producer/HotScoreUpdateProducer.java
- feed/mq/producer/ProfileReconcileProducer.java
- feed/mq/producer/UserBehaviorProducer.java

### feed/properties (1)

- feed/properties/RecommendProperties.java

### feed/service (8)

- feed/service/ContentExposureService.java
- feed/service/ExplicitPreferenceService.java
- feed/service/FeedQueryService.java
- feed/service/FollowFeedService.java
- feed/service/HotContentService.java
- feed/service/RecommendRerankService.java
- feed/service/TopicCatalog.java
- feed/service/UserInterestProfileService.java

### feed/service/impl (9)

- feed/service/impl/BrowseBehaviorSyncTask.java
- feed/service/impl/ContentExposureServiceImpl.java
- feed/service/impl/ExplicitPreferenceServiceImpl.java
- feed/service/impl/FeedQueryServiceImpl.java
- feed/service/impl/FollowFeedServiceImpl.java
- feed/service/impl/HotContentServiceImpl.java
- feed/service/impl/ProfileDecayTask.java
- feed/service/impl/RecommendRerankServiceImpl.java
- feed/service/impl/UserInterestProfileServiceImpl.java

### feed/utils (1)

- feed/utils/HotScoreCalculator.java

## follow（10 个）

### follow/controller/user (1)

- follow/controller/user/FollowController.java

### follow/dto (1)

- follow/dto/FollowStateDTO.java

### follow/entity (1)

- follow/entity/Follow.java

### follow/exception (1)

- follow/exception/FollowException.java

### follow/mapper (1)

- follow/mapper/FollowMapper.java

### follow/service (2)

- follow/service/FollowCommandService.java
- follow/service/FollowQueryService.java

### follow/service/impl (2)

- follow/service/impl/FollowCommandServiceImpl.java
- follow/service/impl/FollowQueryServiceImpl.java

### follow/vo (1)

- follow/vo/FollowResultVO.java

## identity（18 个）

### identity/controller/admin (1)

- identity/controller/admin/IdentityExamController.java

### identity/controller/user (1)

- identity/controller/user/IdentityController.java

### identity/dto (3)

- identity/dto/IdentityAuditDTO.java
- identity/dto/IdentityExamDTO.java
- identity/dto/UserAuthDTO.java

### identity/entity (1)

- identity/entity/UserAuth.java

### identity/enums (1)

- identity/enums/UserAuthDisplayStatus.java

### identity/mapper (2)

- identity/mapper/IdentityExamMapper.java
- identity/mapper/IdentityMapper.java

### identity/service (3)

- identity/service/IdentityExamService.java
- identity/service/IdentityQueryService.java
- identity/service/IdentityService.java

### identity/service/impl (3)

- identity/service/impl/IdentityExamServiceImpl.java
- identity/service/impl/IdentityQueryServiceImpl.java
- identity/service/impl/IdentityServiceImpl.java

### identity/vo (3)

- identity/vo/IdentityDetailVO.java
- identity/vo/IdentityExamVO.java
- identity/vo/UserAuthStatusVO.java

## interaction（34 个）

### interaction/dto (8)

- interaction/dto/CollectStateDTO.java
- interaction/dto/CommentReportDTO.java
- interaction/dto/CommentReportHandleDTO.java
- interaction/dto/CommentReportQueryDTO.java
- interaction/dto/ContentReportDTO.java
- interaction/dto/ContentReportHandleDTO.java
- interaction/dto/ContentReportQueryDTO.java
- interaction/dto/LikeStateDTO.java

### interaction/entity (7)

- interaction/entity/AnswerLiked.java
- interaction/entity/BrowseHistory.java
- interaction/entity/CommentLiked.java
- interaction/entity/CommentReport.java
- interaction/entity/ContentCollect.java
- interaction/entity/ContentLiked.java
- interaction/entity/ContentReport.java

### interaction/mapper (6)

- interaction/mapper/AnswerInteractionMapper.java
- interaction/mapper/BrowseHistoryMapper.java
- interaction/mapper/CommentInteractionMapper.java
- interaction/mapper/CommentReportMapper.java
- interaction/mapper/ContentInteractionMapper.java
- interaction/mapper/ContentReportMapper.java

### interaction/service (5)

- interaction/service/AnswerInteractionService.java
- interaction/service/BrowseHistoryService.java
- interaction/service/CommentInteractionService.java
- interaction/service/ContentInteractionService.java
- interaction/service/ReportGovernanceService.java

### interaction/service/impl (5)

- interaction/service/impl/AnswerInteractionServiceImpl.java
- interaction/service/impl/BrowseHistoryServiceImpl.java
- interaction/service/impl/CommentInteractionServiceImpl.java
- interaction/service/impl/ContentInteractionServiceImpl.java
- interaction/service/impl/ReportGovernanceServiceImpl.java

### interaction/vo (3)

- interaction/vo/BrowseHistorySnapshotVO.java
- interaction/vo/CollectResultVO.java
- interaction/vo/LikeResultVO.java

## moderation（27 个）

### moderation/client (2)

- moderation/client/AliyunImageModerationClient.java
- moderation/client/AliyunTextModerationClient.java

### moderation/config (2)

- moderation/config/AliyunModerationConfig.java
- moderation/config/ModerationMQConfig.java

### moderation/controller/admin (1)

- moderation/controller/admin/AdminModerationController.java

### moderation/dto (1)

- moderation/dto/ModerationTargetQueryDTO.java

### moderation/entity (1)

- moderation/entity/ModerationRecord.java

### moderation/enums (2)

- moderation/enums/ModerationDecision.java
- moderation/enums/ModerationTargetType.java

### moderation/mapper (1)

- moderation/mapper/ModerationRecordMapper.java

### moderation/mq/consumer (1)

- moderation/mq/consumer/ModerationConsumer.java

### moderation/mq/message (1)

- moderation/mq/message/ModerationTaskMessage.java

### moderation/mq/producer (1)

- moderation/mq/producer/ModerationProducer.java

### moderation/policy (1)

- moderation/policy/ModerationDisabledPolicy.java

### moderation/properties (1)

- moderation/properties/AliyunModerationProperties.java

### moderation/result (2)

- moderation/result/ModerationResult.java
- moderation/result/ModerationWorkflowResult.java

### moderation/service (4)

- moderation/service/AdminModerationService.java
- moderation/service/ContentModerationService.java
- moderation/service/ModerationResultService.java
- moderation/service/ModerationWorkflowService.java

### moderation/service/impl (4)

- moderation/service/impl/AdminModerationServiceImpl.java
- moderation/service/impl/ContentModerationServiceImpl.java
- moderation/service/impl/ModerationResultServiceImpl.java
- moderation/service/impl/ModerationWorkflowServiceImpl.java

### moderation/utils (1)

- moderation/utils/SensitiveWordChecker.java

### moderation/vo (1)

- moderation/vo/ModerationRecordVO.java

## notification（14 个）

### notification/config (1)

- notification/config/NotificationMQConfig.java

### notification/controller/user (1)

- notification/controller/user/NotificationController.java

### notification/entity (1)

- notification/entity/Notification.java

### notification/enums (1)

- notification/enums/NotificationType.java

### notification/mapper (1)

- notification/mapper/NotificationMapper.java

### notification/mq/consumer (1)

- notification/mq/consumer/NotificationConsumer.java

### notification/mq/message (1)

- notification/mq/message/NotificationEventMessage.java

### notification/mq/producer (2)

- notification/mq/producer/NotificationEventProducer.java
- notification/mq/producer/NotificationProducer.java

### notification/service (2)

- notification/service/NotificationConsumeService.java
- notification/service/NotificationService.java

### notification/service/impl (2)

- notification/service/impl/NotificationConsumeServiceImpl.java
- notification/service/impl/NotificationServiceImpl.java

### notification/vo (1)

- notification/vo/NotificationVO.java

## platform（99 个）

### platform/audit/annotation (1)

- platform/audit/annotation/AdminAudit.java

### platform/audit/aop (1)

- platform/audit/aop/AdminAuditAspect.java

### platform/audit/constant (1)

- platform/audit/constant/AdminAuditActionConstants.java

### platform/audit/controller/admin (1)

- platform/audit/controller/admin/AdminAuditLogController.java

### platform/audit/dto (1)

- platform/audit/dto/AdminAuditLogQueryDTO.java

### platform/audit/entity (1)

- platform/audit/entity/AdminAuditLog.java

### platform/audit/mapper (1)

- platform/audit/mapper/AdminAuditLogMapper.java

### platform/audit/service (2)

- platform/audit/service/AdminAuditLogService.java
- platform/audit/service/AdminAuditRecorder.java

### platform/audit/service/impl (3)

- platform/audit/service/impl/AdminAuditFailureWriter.java
- platform/audit/service/impl/AdminAuditLogServiceImpl.java
- platform/audit/service/impl/AdminAuditRecorderImpl.java

### platform/common/constant (1)

- platform/common/constant/SystemConstant.java

### platform/common/enums (1)

- platform/common/enums/AuditStatus.java

### platform/common/exception (2)

- platform/common/exception/BaseException.java
- platform/common/exception/NoFoundException.java

### platform/common/result (4)

- platform/common/result/PageResult.java
- platform/common/result/PageVO.java
- platform/common/result/Result.java
- platform/common/result/ScrollResult.java

### platform/mq/admin/controller (1)

- platform/mq/admin/controller/AdminEventController.java

### platform/mq/admin/dto (2)

- platform/mq/admin/dto/InboxEventQueryDTO.java
- platform/mq/admin/dto/OutboxEventQueryDTO.java

### platform/mq/admin/service (1)

- platform/mq/admin/service/AdminEventService.java

### platform/mq/admin/service/impl (1)

- platform/mq/admin/service/impl/AdminEventServiceImpl.java

### platform/mq/admin/vo (3)

- platform/mq/admin/vo/EventOverviewVO.java
- platform/mq/admin/vo/EventRetryDistributionVO.java
- platform/mq/admin/vo/EventStatusCountVO.java

### platform/mq/config (2)

- platform/mq/config/OutboxSchedulingConfig.java
- platform/mq/config/RabbitMQConfig.java

### platform/mq/entity (2)

- platform/mq/entity/InboxEvent.java
- platform/mq/entity/OutboxEvent.java

### platform/mq/enums (4)

- platform/mq/enums/InboxAcquireResult.java
- platform/mq/enums/InboxEventStatus.java
- platform/mq/enums/OutboxEventStatus.java
- platform/mq/enums/OutboxEventType.java

### platform/mq/exception (1)

- platform/mq/exception/OutboxInsertFailedException.java

### platform/mq/mapper (2)

- platform/mq/mapper/InboxEventMapper.java
- platform/mq/mapper/OutboxEventMapper.java

### platform/mq/message (1)

- platform/mq/message/OutboxRoute.java

### platform/mq/outbox (2)

- platform/mq/outbox/OutboxDispatcher.java
- platform/mq/outbox/OutboxRouteRegistry.java

### platform/mq/producer (2)

- platform/mq/producer/OutboxEventAppender.java
- platform/mq/producer/ReliableRabbitPublisher.java

### platform/mq/properties (2)

- platform/mq/properties/OutboxDispatchProperties.java
- platform/mq/properties/OutboxMaintenanceProperties.java

### platform/mq/service (2)

- platform/mq/service/InboxEventService.java
- platform/mq/service/OutboxEventService.java

### platform/mq/service/impl (3)

- platform/mq/service/impl/InboxEventServiceImpl.java
- platform/mq/service/impl/OutboxEventServiceImpl.java
- platform/mq/service/impl/OutboxMaintenanceService.java

### platform/oss/config (1)

- platform/oss/config/OssConfiguration.java

### platform/oss/properties (1)

- platform/oss/properties/AliOssProperties.java

### platform/oss/service (1)

- platform/oss/service/AliOssService.java

### platform/redis/constant (1)

- platform/redis/constant/RedisConstants.java

### platform/redis/properties (1)

- platform/redis/properties/ReadPathCacheProperties.java

### platform/redis/utils (1)

- platform/redis/utils/RedisTaskLockAdapter.java

### platform/security/annotation (1)

- platform/security/annotation/RateLimit.java

### platform/security/aop (1)

- platform/security/aop/RateLimitAspect.java

### platform/security/config (1)

- platform/security/config/SecurityConfiguration.java

### platform/security/constant (4)

- platform/security/constant/JwtClaimsConstant.java
- platform/security/constant/PermissionConstants.java
- platform/security/constant/RoleConstants.java
- platform/security/constant/RolePermissionMapping.java

### platform/security/context (1)

- platform/security/context/BaseContext.java

### platform/security/controller/admin (1)

- platform/security/controller/admin/AdminRoleController.java

### platform/security/enums (1)

- platform/security/enums/TokenAuthenticationFailureReason.java

### platform/security/exception (3)

- platform/security/exception/AuthFailedException.java
- platform/security/exception/RateLimitExceededException.java
- platform/security/exception/TokenAuthenticationException.java

### platform/security/filter (1)

- platform/security/filter/OptionalJwtAuthenticationFilter.java

### platform/security/handler (2)

- platform/security/handler/SecurityAccessDeniedHandler.java
- platform/security/handler/SecurityAuthenticationEntryPoint.java

### platform/security/mapper (1)

- platform/security/mapper/UserRoleMapper.java

### platform/security/model (3)

- platform/security/model/AuthenticatedUser.java
- platform/security/model/AuthenticationSnapshot.java
- platform/security/model/RateLimitDecision.java

### platform/security/properties (3)

- platform/security/properties/JwtProperties.java
- platform/security/properties/QuantabotProperties.java
- platform/security/properties/SecurityProperties.java

### platform/security/service (6)

- platform/security/service/AdminRoleService.java
- platform/security/service/AuthenticationSnapshotCache.java
- platform/security/service/RateLimitService.java
- platform/security/service/SessionService.java
- platform/security/service/TokenAuthenticationService.java
- platform/security/service/UserAccessStateService.java

### platform/security/service/impl (6)

- platform/security/service/impl/AdminRoleServiceImpl.java
- platform/security/service/impl/AuthenticationSnapshotCacheImpl.java
- platform/security/service/impl/RateLimitServiceImpl.java
- platform/security/service/impl/SessionServiceImpl.java
- platform/security/service/impl/TokenAuthenticationServiceImpl.java
- platform/security/service/impl/UserAccessStateServiceImpl.java

### platform/security/utils (1)

- platform/security/utils/JwtUtil.java

### platform/security/vo (1)

- platform/security/vo/SecurityContextVO.java

### platform/web/config (2)

- platform/web/config/OpenAPIConfiguration.java
- platform/web/config/WebMvcConfiguration.java

### platform/web/controller (1)

- platform/web/controller/CommonController.java

### platform/web/handler (1)

- platform/web/handler/GlobalExceptionHandler.java

### platform/websocket/config (1)

- platform/websocket/config/WebSocketConfig.java

## rag（28 个）

### rag/config (4)

- rag/config/DeepSeekChatConfig.java
- rag/config/QwenEmbeddingConfig.java
- rag/config/RagConfig.java
- rag/config/VectorStoreConfig.java

### rag/controller/user (1)

- rag/controller/user/RagController.java

### rag/exception (1)

- rag/exception/RagRetrieveException.java

### rag/generation (4)

- rag/generation/RagGenerationService.java
- rag/generation/RagPromptTemplateService.java
- rag/generation/RagSearchService.java
- rag/generation/RagSummaryCacheKeyBuilder.java

### rag/model (5)

- rag/model/RagAnswer.java
- rag/model/RagCandidate.java
- rag/model/RagContextDocument.java
- rag/model/RagSearchRequest.java
- rag/model/RagSearchResponse.java

### rag/properties (1)

- rag/properties/RagProperties.java

### rag/retrieval (4)

- rag/retrieval/EsRecallService.java
- rag/retrieval/RagFusionService.java
- rag/retrieval/RagRetrieveFacade.java
- rag/retrieval/VectorRecallService.java

### rag/vector (8)

- rag/vector/AnswerRagDocumentConverter.java
- rag/vector/AnswerVectorSyncService.java
- rag/vector/ContentRagDocumentConverter.java
- rag/vector/ContentVectorSyncService.java
- rag/vector/RagChunkDocumentConverter.java
- rag/vector/RagTextChunker.java
- rag/vector/VectorStoreInitializer.java
- rag/vector/VectorStorePersistenceService.java

## search（41 个）

### search/config (2)

- search/config/ElasticsearchConfig.java
- search/config/SearchMQConfig.java

### search/constant (1)

- search/constant/EsIndexConstant.java

### search/controller/user (1)

- search/controller/user/SearchController.java

### search/dto (1)

- search/dto/SearchDTO.java

### search/entity (1)

- search/entity/SearchHistory.java

### search/es/document (2)

- search/es/document/AnswerDocument.java
- search/es/document/ContentDocument.java

### search/es/initializer (1)

- search/es/initializer/ElasticsearchIndexInitializer.java

### search/es/mapper (2)

- search/es/mapper/AnswerDocumentMapper.java
- search/es/mapper/ContentDocumentMapper.java

### search/es/query (1)

- search/es/query/ElasticsearchQueryFactory.java

### search/exception (1)

- search/exception/SearchFailedException.java

### search/mapper (1)

- search/mapper/SearchMapper.java

### search/mq/consumer (1)

- search/mq/consumer/SearchReconcileConsumer.java

### search/mq/message (1)

- search/mq/message/SearchReconcileMessage.java

### search/mq/producer (2)

- search/mq/producer/SearchEventProducer.java
- search/mq/producer/SearchReconcileProducer.java

### search/properties (1)

- search/properties/SearchTrendingProperties.java

### search/result (2)

- search/result/EsPageResult.java
- search/result/ReindexResult.java

### search/service (8)

- search/service/AnswerSearchService.java
- search/service/ContentIndexService.java
- search/service/ContentSearchService.java
- search/service/SearchReconcileService.java
- search/service/SearchReindexService.java
- search/service/SearchService.java
- search/service/TrendingCacheInvalidator.java
- search/service/TrendingCacheService.java

### search/service/impl (9)

- search/service/impl/AnswerSearchServiceImpl.java
- search/service/impl/ContentIndexServiceImpl.java
- search/service/impl/ContentSearchServiceImpl.java
- search/service/impl/SearchReconcileServiceImpl.java
- search/service/impl/SearchReindexServiceImpl.java
- search/service/impl/SearchServiceImpl.java
- search/service/impl/TrendingCacheInvalidatorImpl.java
- search/service/impl/TrendingCacheServiceImpl.java
- search/service/impl/TrendingDataLoader.java

### search/vo (3)

- search/vo/HotAlumniVO.java
- search/vo/HotQuestionVO.java
- search/vo/SearchTrendingVO.java

## user（29 个）

### user/controller/admin (1)

- user/controller/admin/AdminUserController.java

### user/controller/user (1)

- user/controller/user/UserController.java

### user/dto (3)

- user/dto/UserAdminQueryDTO.java
- user/dto/UserInfoDTO.java
- user/dto/UserLoginDTO.java

### user/entity (1)

- user/entity/User.java

### user/exception (2)

- user/exception/LoginFailedException.java
- user/exception/UserInfoFailedException.java

### user/mapper (1)

- user/mapper/UserMapper.java

### user/properties (1)

- user/properties/WeChatProperties.java

### user/service (6)

- user/service/AdminUserService.java
- user/service/AuthorProfileCache.java
- user/service/UserAccountService.java
- user/service/UserProfileService.java
- user/service/UserQueryService.java
- user/service/UserReadCacheInvalidator.java

### user/service/impl (6)

- user/service/impl/AdminUserServiceImpl.java
- user/service/impl/AuthorProfileCacheImpl.java
- user/service/impl/UserAccountServiceImpl.java
- user/service/impl/UserProfileServiceImpl.java
- user/service/impl/UserQueryServiceImpl.java
- user/service/impl/UserReadCacheInvalidatorImpl.java

### user/utils (1)

- user/utils/HttpClientUtil.java

### user/vo (6)

- user/vo/AdminUserDetailVO.java
- user/vo/UserAccountVO.java
- user/vo/UserAuthInfoVO.java
- user/vo/UserInfoVO.java
- user/vo/UserLoginVO.java
- user/vo/UserProfileVO.java

## 3. Mapper XML 清单（实际 21 个）

以下路径均相对 src/main/resources/mapper/，与上面的 Mapper 所属域成对维护。

### mapper/answer (1)

- src/main/resources/mapper/answer/QuestionMapper.xml

### mapper/comment (1)

- src/main/resources/mapper/comment/CommentMapper.xml

### mapper/content (2)

- src/main/resources/mapper/content/BotContentSyncMapper.xml
- src/main/resources/mapper/content/ContentMapper.xml

### mapper/feed (1)

- src/main/resources/mapper/feed/UserProfileSignalMapper.xml

### mapper/follow (1)

- src/main/resources/mapper/follow/FollowMapper.xml

### mapper/identity (2)

- src/main/resources/mapper/identity/IdentityExamMapper.xml
- src/main/resources/mapper/identity/IdentityMapper.xml

### mapper/interaction (6)

- src/main/resources/mapper/interaction/AnswerInteractionMapper.xml
- src/main/resources/mapper/interaction/BrowseHistoryMapper.xml
- src/main/resources/mapper/interaction/CommentInteractionMapper.xml
- src/main/resources/mapper/interaction/CommentReportMapper.xml
- src/main/resources/mapper/interaction/ContentInteractionMapper.xml
- src/main/resources/mapper/interaction/ContentReportMapper.xml

### mapper/moderation (1)

- src/main/resources/mapper/moderation/ModerationRecordMapper.xml

### mapper/notification (1)

- src/main/resources/mapper/notification/NotificationMapper.xml

### mapper/platform/audit (1)

- src/main/resources/mapper/platform/audit/AdminAuditLogMapper.xml

### mapper/platform/mq (2)

- src/main/resources/mapper/platform/mq/InboxEventMapper.xml
- src/main/resources/mapper/platform/mq/OutboxEventMapper.xml

### mapper/search (1)

- src/main/resources/mapper/search/SearchMapper.xml

### mapper/user (1)

- src/main/resources/mapper/user/UserMapper.xml

## 4. 清单口径

- Java 数量口径：当前 src/main/java/com/quanta/demo0 下所有以 .java 结尾的生产文件，包含根包 Demo0Application.java。
- XML 数量口径：当前 src/main/resources/mapper 下所有以 .xml 结尾的 Mapper 文件，包含按域递归目录。
- 本文是最终包结构和文件清单，不是执行结果记录；任何验证状态应以实际命令输出和对应验收文档为准。
