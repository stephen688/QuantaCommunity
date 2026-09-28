package com.quanta.demo0.service.Impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.quanta.demo0.annotation.ModerationTargetType;
import com.quanta.demo0.entity.Content;
import com.quanta.demo0.entity.ContentComment;
import com.quanta.demo0.platform.mq.entity.OutboxEvent;
import com.quanta.demo0.entity.QuestionAnswer;
import com.quanta.demo0.platform.mq.enums.OutboxEventStatus;
import com.quanta.demo0.platform.mq.enums.OutboxEventType;
import com.quanta.demo0.exception.ContentFailedException;
import com.quanta.demo0.exception.CommentFailedException;
import com.quanta.demo0.mapper.OutboxEventMapper;
import com.quanta.demo0.mq.message.*;
import com.quanta.demo0.platform.mq.properties.OutboxDispatchProperties;
import com.quanta.demo0.service.OutboxEventService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class OutboxEventServiceImpl implements OutboxEventService {

    /**
     * Outbox payload 最大 32 KB。
     */
    private static final int MAX_PAYLOAD_BYTES = 32 * 1024;

    /**
     * 防止单个异常图片 URL 撑大整条消息。
     */
    private static final int MAX_IMAGE_URL_BYTES = 4096;

    private final OutboxEventMapper outboxEventMapper;
    private final ObjectMapper objectMapper;
    private final OutboxDispatchProperties outboxDispatchProperties;

    /**
     * 创建帖子审核事件。
     *
     * 默认事务传播方式是 REQUIRED：
     * ContentServiceImpl.publish 已经有事务时，
     * 这里会加入同一个事务，不会新开事务。
     */
    @Override
    @Transactional
    public String createContentModerationEvent(
            Content content,
            List<String> imageUrls
    ) {
        List<String> safeImageUrls =
                imageUrls == null ? List.of() : List.copyOf(imageUrls);

        validateImageUrls(safeImageUrls);

        String eventId = UUID.randomUUID().toString();
        LocalDateTime occurredAt = LocalDateTime.now();

        /**
         * 这里只组装审核真正需要的数据，
         * 不序列化完整 Content 实体。
         */
        ModerationTaskMessage message = ModerationTaskMessage.builder()
                .eventId(eventId)
                .eventType(OutboxEventType.MODERATION_REQUESTED.getCode())
                .occurredAt(occurredAt)
                .targetType(ModerationTargetType.CONTENT)
                .targetId(content.getContentId())
                .publisherUserId(content.getPublishUserId())
                .title(content.getTitle())
                .content(content.getContent())
                .imageUrls(safeImageUrls)
                .createdAt(occurredAt)
                .retryCount(0)
                .build();

        String payload = serializePayload(message);
        validatePayloadSize(payload);

        OutboxEvent event = OutboxEvent.builder()
                .eventId(eventId)
                .eventType(OutboxEventType.MODERATION_REQUESTED.getCode())
                .aggregateType(ModerationTargetType.CONTENT.name())
                .aggregateId(content.getContentId())
                .payload(payload)
                .status(OutboxEventStatus.PENDING.getCode())
                .retryCount(0)
                .nextRetryTime(occurredAt)
                .replayCount(0)
                .build();

        int insertedRows = outboxEventMapper.insert(event);

        /**
         * 插入失败必须抛异常。
         * 因为这是 RuntimeException，
         * 外层 publish 事务会一起回滚：
         * 帖子回滚
         * 图片回滚
         * Outbox 回滚
         */
        if (insertedRows != 1) {
            throw new ContentFailedException("创建帖子审核任务失败");
        }

        return eventId;
    }

    /**
     * 创建通知 Outbox 事件。
     *
     * 调用方已经有事务时，
     * 本方法会加入调用方事务。
     */
    @Override
    @Transactional
    public String createNotificationEvent(
            NotificationEventMessage message,
            String aggregateType,
            Long aggregateId
    ) {
        String eventId =
                UUID.randomUUID().toString();

        LocalDateTime occurredAt =
                LocalDateTime.now();

        /**
         * Outbox 和 RabbitMQ 使用相同 eventId。
         */
        message.setEventId(eventId);
        message.setEventType(
                OutboxEventType
                        .NOTIFICATION_REQUESTED
                        .getCode()
        );
        message.setOccurredAt(occurredAt);
        message.setCreatedAt(occurredAt);
        message.setRetryCount(0);

        String payload =
                serializePayload(message);

        validatePayloadSize(payload);

        OutboxEvent event =
                OutboxEvent.builder()
                        .eventId(eventId)
                        .eventType(
                                OutboxEventType
                                        .NOTIFICATION_REQUESTED
                                        .getCode()
                        )
                        .aggregateType(aggregateType)
                        .aggregateId(aggregateId)
                        .payload(payload)
                        .status(
                                OutboxEventStatus
                                        .PENDING
                                        .getCode()
                        )
                        .retryCount(0)
                        .nextRetryTime(occurredAt)
                        .replayCount(0)
                        .build();

        int insertedRows =
                outboxEventMapper.insert(event);

        if (insertedRows != 1) {
            throw new IllegalStateException(
                    "创建通知 Outbox 事件失败"
            );
        }

        return eventId;
    }



    @Override
    @Transactional
    public String createAnswerModerationEvent(QuestionAnswer answer) {
        String eventId = UUID.randomUUID().toString();
        LocalDateTime occurredAt = LocalDateTime.now();

        // 回答没有标题和图片，只保存审核真正需要的数据。
        ModerationTaskMessage message = ModerationTaskMessage.builder()
                .eventId(eventId)
                .eventType(OutboxEventType.MODERATION_REQUESTED.getCode())
                .occurredAt(occurredAt)
                .targetType(ModerationTargetType.ANSWER)
                .targetId(answer.getAnswerId())
                .publisherUserId(answer.getUserId())
                .title(null)
                .content(answer.getContent())
                .imageUrls(List.of())
                .createdAt(occurredAt)
                .retryCount(0)
                .build();

        String payload = serializePayload(message);

        // 回答正文也必须经过统一的 32 KB 限制。
        validatePayloadSize(payload);

        OutboxEvent event = OutboxEvent.builder()
                .eventId(eventId)
                .eventType(OutboxEventType.MODERATION_REQUESTED.getCode())
                .aggregateType(ModerationTargetType.ANSWER.name())
                .aggregateId(answer.getAnswerId())
                .payload(payload)
                .status(OutboxEventStatus.PENDING.getCode())
                .retryCount(0)
                .nextRetryTime(occurredAt)
                .replayCount(0)
                .build();

        int insertedRows = outboxEventMapper.insert(event);

        // 抛出运行时异常，让回答和 Outbox 一起回滚。
        if (insertedRows != 1) {
            throw new ContentFailedException("创建回答审核任务失败");
        }

        return eventId;
    }

    @Override
    @Transactional
    public String createFeedUpsertEvent(Content content) {
        validateFeedContent(content);

        String eventId = UUID.randomUUID().toString();
        LocalDateTime occurredAt = LocalDateTime.now();
        LocalDateTime contentCreateTime = content.getCreateTime() != null ? content.getCreateTime() : occurredAt;
        Long createTime = contentCreateTime.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();

        FeedPushMessage message = FeedPushMessage.builder()
                .eventId(eventId)
                .eventType(OutboxEventType.FEED_UPSERT_REQUESTED.getCode())
                .occurredAt(occurredAt)
                .contentId(content.getContentId())
                .publishUserId(content.getPublishUserId())
                .contentType(content.getContentType())
                .createTime(createTime)
                .messageTime(occurredAt)
                .retryCount(0)
                .build();

        return saveFeedEvent(message, eventId, OutboxEventType.FEED_UPSERT_REQUESTED, content.getContentId());
    }
    @Override
    @Transactional
    public String createFeedDeleteEvent(Content content) {
        validateFeedContent(content);

        String eventId = UUID.randomUUID().toString();
        LocalDateTime occurredAt = LocalDateTime.now();

        FeedDeleteMessage message = FeedDeleteMessage.builder()
                .eventId(eventId)
                .eventType(OutboxEventType.FEED_DELETE_REQUESTED.getCode())
                .occurredAt(occurredAt)
                .contentId(content.getContentId())
                .publishUserId(content.getPublishUserId())
                .contentType(content.getContentType())
                .deleteTime(occurredAt)
                .retryCount(0)
                .build();

        return saveFeedEvent(message, eventId, OutboxEventType.FEED_DELETE_REQUESTED, content.getContentId());
    }

    @Override
    @Transactional
    public String createHotScoreRecalculateEvent(Long contentId, String triggerType) {
        if (contentId == null) {
            throw new ContentFailedException("热度事件缺少帖子 ID");
        }

        String eventId = UUID.randomUUID().toString();
        LocalDateTime occurredAt = LocalDateTime.now();

        HotScoreMessage message = HotScoreMessage.builder()
                .eventId(eventId)
                .eventType(OutboxEventType.HOT_SCORE_RECALCULATE_REQUESTED.getCode())
                .occurredAt(occurredAt)
                .contentId(contentId)
                .triggerType(triggerType)
                .retryCount(0)
                .eventTime(occurredAt) // 兼容原来的字段
                .build();

        String payload = serializePayload(message);
        validatePayloadSize(payload);

        OutboxEvent event = OutboxEvent.builder()
                .eventId(eventId)
                .eventType(OutboxEventType.HOT_SCORE_RECALCULATE_REQUESTED.getCode())
                .aggregateType(ModerationTargetType.CONTENT.name())
                .aggregateId(contentId)
                .payload(payload)
                .status(OutboxEventStatus.PENDING.getCode())
                .retryCount(0)
                .nextRetryTime(occurredAt)
                .replayCount(0)
                .build();

        // 插入失败必须抛异常，让业务数据和热度 Outbox 一起回滚。
        if (outboxEventMapper.insert(event) != 1) {
            throw new ContentFailedException("创建热度重新计算事件失败");
        }

        return eventId;
    }

    /**
     * 创建用户行为事件（画像更新信号，D2）。
     *
     * 与 createHotScoreRecalculateEvent 同构：eventId 用 UUID 族，
     * 调用方已有事务时加入同一事务，插入失败抛异常让业务写一起回滚。
     * 聚合主体是用户（画像按 userId 组织），aggregateId 记 userId。
     */
    @Override
    @Transactional
    public String createUserBehaviorEvent(Long userId, Long contentId, String behaviorType) {
        // 赞/藏/评路径：事件每次真正新增才创建，UUID 族 eventId 即可满足 Inbox 幂等
        return createUserBehaviorEvent(userId, contentId, behaviorType, null);
    }

    /**
     * 创建用户行为事件（画像更新信号，D2），支持外部指定稳定 eventId。
     *
     * 浏览对账任务传 user.behavior.browse:{browseHistoryId} 固定格式，
     * watermark 未推进导致的同一行重复转发被 Inbox 幂等挡住；
     * eventId 为 null 时内部生成 UUID（与三参版本行为一致）。
     */
    @Override
    @Transactional
    public String createUserBehaviorEvent(Long userId, Long contentId, String behaviorType, String eventId) {
        // 参数校验：任一必填字段为空即拒绝，不写入毒丸事件。
        if (userId == null || contentId == null) {
            throw new ContentFailedException("用户行为事件缺少用户 ID 或帖子 ID");
        }
        validateBehaviorType(behaviorType);

        String finalEventId = eventId != null ? eventId : UUID.randomUUID().toString();
        LocalDateTime occurredAt = LocalDateTime.now();

        // 消息不携带权重，权重换算由画像消费侧承担。
        UserBehaviorMessage message = UserBehaviorMessage.builder()
                .eventId(finalEventId)
                .eventType(OutboxEventType.USER_BEHAVIOR_REQUESTED.getCode())
                .occurredAt(occurredAt)
                .userId(userId)
                .contentId(contentId)
                .behaviorType(behaviorType)
                .retryCount(0)
                .build();

        String payload = serializePayload(message);
        validatePayloadSize(payload);

        OutboxEvent event = OutboxEvent.builder()
                .eventId(finalEventId)
                .eventType(OutboxEventType.USER_BEHAVIOR_REQUESTED.getCode())
                .aggregateType("USER")
                .aggregateId(userId)
                .payload(payload)
                .status(OutboxEventStatus.PENDING.getCode())
                .retryCount(0)
                .nextRetryTime(occurredAt)
                .replayCount(0)
                .build();

        // 插入失败必须抛异常，让业务写（赞/藏/评）和行为 Outbox 一起回滚。
        if (outboxEventMapper.insert(event) != 1) {
            throw new ContentFailedException("创建用户行为事件失败");
        }

        return finalEventId;
    }

    /**
     * 行为类型白名单校验：只允许 D2 定义的四种行为。
     */
    private void validateBehaviorType(String behaviorType) {
        if (behaviorType == null || !List.of("LIKE", "COLLECT", "COMMENT", "VIEW").contains(behaviorType)) {
            throw new ContentFailedException("用户行为类型非法：" + behaviorType);
        }
    }

    @Override
    @Transactional
    public String createCommentModerationEvent(ContentComment comment, List<String> imageUrls) {
        List<String> safeImageUrls = imageUrls == null ? List.of() : List.copyOf(imageUrls);
        validateImageUrls(safeImageUrls);

        String eventId = UUID.randomUUID().toString();
        LocalDateTime occurredAt = LocalDateTime.now();

        // 评论审核只携带正文和图片，不把完整评论实体写入 Outbox。
        ModerationTaskMessage message = ModerationTaskMessage.builder()
                .eventId(eventId)
                .eventType(OutboxEventType.MODERATION_REQUESTED.getCode())
                .occurredAt(occurredAt)
                .targetType(ModerationTargetType.COMMENT)
                .targetId(comment.getCommentId())
                .publisherUserId(comment.getUserId())
                .title(null)
                .content(comment.getContent())
                .imageUrls(safeImageUrls)
                .createdAt(occurredAt)
                .retryCount(0)
                .build();

        String payload = serializePayload(message);
        validatePayloadSize(payload);

        OutboxEvent event = OutboxEvent.builder()
                .eventId(eventId)
                .eventType(OutboxEventType.MODERATION_REQUESTED.getCode())
                .aggregateType(ModerationTargetType.COMMENT.name())
                .aggregateId(comment.getCommentId())
                .payload(payload)
                .status(OutboxEventStatus.PENDING.getCode())
                .retryCount(0)
                .nextRetryTime(occurredAt)
                .replayCount(0)
                .build();

        // 插入失败抛出运行时异常，让评论、图片和 Outbox 一起回滚。
        if (outboxEventMapper.insert(event) != 1) {
            throw new CommentFailedException("创建评论审核任务失败");
        }

        return eventId;
    }

    /**
     * 审核通过的评论命中 bot 时写入触发 Outbox，和审核副作用共用调用方事务。
     */
    @Override
    @Transactional
    public String createBotMentionEvent(
            ContentComment comment,
            List<String> imageUrls,
            String botTriggerKind
    ) {
        validateBotTriggerKind(botTriggerKind);
        List<String> safeImageUrls = imageUrls == null ? List.of() : List.copyOf(imageUrls);
        validateImageUrls(safeImageUrls);

        String eventId = UUID.randomUUID().toString();
        LocalDateTime occurredAt = LocalDateTime.now();

        BotMentionMessage message = BotMentionMessage.builder()
                .eventId(eventId)
                .eventType(OutboxEventType.BOT_MENTION_REQUESTED.getCode())
                .occurredAt(occurredAt)
                .retryCount(0)
                .commentId(comment.getCommentId())
                .postId(comment.getContentId())
                .answerId(comment.getAnswerId())
                .commenterUserId(comment.getUserId())
                .commentContent(comment.getContent())
                .commentImages(safeImageUrls)
                .mentionedBot(true)
                .botTriggerKind(botTriggerKind)
                .parentId(comment.getParentId())
                .replyCommentId(comment.getReplyCommentId())
                .build();

        String payload = serializePayload(message);
        validatePayloadSize(payload);

        OutboxEvent event = OutboxEvent.builder()
                .eventId(eventId)
                .eventType(OutboxEventType.BOT_MENTION_REQUESTED.getCode())
                .aggregateType(ModerationTargetType.COMMENT.name())
                .aggregateId(comment.getCommentId())
                .payload(payload)
                .status(OutboxEventStatus.PENDING.getCode())
                .retryCount(0)
                .nextRetryTime(occurredAt)
                .replayCount(0)
                .build();

        // Outbox 写入失败必须抛异常，让审核状态与其它副作用一起回滚。
        if (outboxEventMapper.insert(event) != 1) {
            throw new CommentFailedException("创建 bot 触发事件失败");
        }

        return eventId;
    }

    private void validateBotTriggerKind(String botTriggerKind) {
        if (!"mentioned".equals(botTriggerKind) && !"replied".equals(botTriggerKind)) {
            throw new CommentFailedException("bot 触发类型非法");
        }
    }

    /** 主题打标事件只带内容 ID，消费者必须读取审核后的当前事实。 */
    @Override
    @Transactional
    public String createContentTopicTagEvent(Long contentId) {
        if (contentId == null || contentId <= 0) {
            throw new ContentFailedException("主题打标缺少有效帖子 ID");
        }
        // 词表初版每帖一个持久任务；回填游标重跑与重复审核不能绕过 Inbox 再付费。
        // DEAD 的人工补偿沿用原 eventId 重放，不另造任务。
        String eventId = UUID.nameUUIDFromBytes(("content-topic-v1:" + contentId)
                .getBytes(StandardCharsets.UTF_8)).toString();
        ContentTopicTagMessage message = ContentTopicTagMessage.builder().eventId(eventId)
                .eventType(OutboxEventType.CONTENT_TOPIC_TAG_REQUESTED.getCode()).contentId(contentId)
                .occurredAt(LocalDateTime.now()).retryCount(0).build();
        return saveRecommendationEvent(message, eventId, OutboxEventType.CONTENT_TOPIC_TAG_REQUESTED, "CONTENT", contentId);
    }

    /** 保存显式画像校准事件；失败回滚调用方刚写入的偏好事实。 */
    @Override
    @Transactional
    public String createProfileUpdatedEvent(Long userId, String eventId) {
        if (userId == null || userId <= 0 || !StringUtils.hasText(eventId)) {
            throw new ContentFailedException("显式画像事件缺少必要标识");
        }
        ProfileReconcileMessage message = ProfileReconcileMessage.builder().eventId(eventId)
                .eventType(OutboxEventType.USER_PROFILE_UPDATED.getCode()).userId(userId)
                .occurredAt(LocalDateTime.now()).retryCount(0).build();
        return saveRecommendationEvent(message, eventId, OutboxEventType.USER_PROFILE_UPDATED, "USER", userId);
    }

    private String saveRecommendationEvent(Object message, String eventId, OutboxEventType type,
            String aggregateType, Long aggregateId) {
        String payload = serializePayload(message);
        validatePayloadSize(payload);
        OutboxEvent event = OutboxEvent.builder().eventId(eventId).eventType(type.getCode())
                .aggregateType(aggregateType).aggregateId(aggregateId).payload(payload)
                .status(OutboxEventStatus.PENDING.getCode()).retryCount(0)
                .nextRetryTime(LocalDateTime.now()).replayCount(0).build();
        if (type == OutboxEventType.CONTENT_TOPIC_TAG_REQUESTED) {
            // 唯一 event_id 的原子 no-op upsert，避免“先查后插”并发竞态；其它事件保持原语义。
            outboxEventMapper.insertIfAbsent(event);
        } else if (outboxEventMapper.insert(event) != 1) {
            throw new ContentFailedException("推荐异步事件写入失败");
        }
        return eventId;
    }


    @Override
    @Transactional
    public String createSearchReconcileEvent(String targetType, Long targetId, String triggerType) {
        if (!StringUtils.hasText(targetType) || targetId == null) {
            throw new ContentFailedException("ES 校准事件缺少必要信息");
        }

        if (!ModerationTargetType.CONTENT.name().equals(targetType) && !ModerationTargetType.ANSWER.name().equals(targetType)) {
            throw new ContentFailedException("ES 校准事件只支持 CONTENT 或 ANSWER");
        }

        String eventId = UUID.randomUUID().toString();
        LocalDateTime occurredAt = LocalDateTime.now();

        SearchReconcileMessage message = SearchReconcileMessage.builder()
                .eventId(eventId)
                .eventType(OutboxEventType.SEARCH_RECONCILE_REQUESTED.getCode())
                .occurredAt(occurredAt)
                .targetType(targetType)
                .targetId(targetId)
                .triggerType(triggerType)
                .retryCount(0)
                .build();

        String payload = serializePayload(message);
        validatePayloadSize(payload);

        OutboxEvent event = OutboxEvent.builder()
                .eventId(eventId)
                .eventType(OutboxEventType.SEARCH_RECONCILE_REQUESTED.getCode())
                .aggregateType(targetType)
                .aggregateId(targetId)
                .payload(payload)
                .status(OutboxEventStatus.PENDING.getCode())
                .retryCount(0)
                .nextRetryTime(occurredAt)
                .replayCount(0)
                .build();

        // ES 事件插入失败时抛异常，让业务修改和 Outbox 一起回滚。
        if (outboxEventMapper.insert(event) != 1) {
            throw new ContentFailedException("创建 ES 校准事件失败");
        }

        return eventId;
    }


    /**
     * 在短事务中查询并抢占一批等待发送的事件。
     * RabbitMQ 发送在事务提交后由 OutboxDispatcher 执行。
     */
    @Override
    @Transactional
    public List<OutboxEvent> claimBatch(String instanceId) {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime lockedUntil = now.plusSeconds(
                outboxDispatchProperties.getLeaseSeconds()
        );

        List<OutboxEvent> candidates =
                outboxEventMapper.selectClaimableForUpdate(
                        now,
                        outboxDispatchProperties.getBatchSize()
                );

        List<OutboxEvent> claimedEvents = new ArrayList<>();

        for (OutboxEvent event : candidates) {
            int updatedRows = outboxEventMapper.claimForProcessing(
                    event.getId(),
                    instanceId,
                    lockedUntil,
                    now
            );

            if (updatedRows == 1) {
                event.setStatus(OutboxEventStatus.PROCESSING.getCode());
                event.setLockedBy(instanceId);
                event.setLockedUntil(lockedUntil);
                claimedEvents.add(event);
            }
        }

        return claimedEvents;
    }

    /**
     * RabbitMQ Confirm ACK 且消息未被 Return 时标记发送成功。
     */
    @Override
    @Transactional
    public boolean markSent(Long id, String instanceId) {
        int updatedRows = outboxEventMapper.markSentByOwner(
                id,
                instanceId,
                LocalDateTime.now()
        );
        return updatedRows == 1;
    }

    /**
     * 发送失败后回到 PENDING，并记录下一次重试时间。
     */
    @Override
    @Transactional
    public boolean markRetry(
            Long id,
            String instanceId,
            Integer retryCount,
            LocalDateTime nextRetryTime,
            String lastError
    ) {
        int updatedRows = outboxEventMapper.markRetryByOwner(
                id,
                instanceId,
                retryCount,
                nextRetryTime,
                truncateError(lastError)
        );
        return updatedRows == 1;
    }

    /**
     * 自动重试耗尽后标记 DEAD。
     */
    @Override
    @Transactional
    public boolean markDead(
            Long id,
            String instanceId,
            String lastError
    ) {
        int updatedRows = outboxEventMapper.markDeadByOwner(
                id,
                instanceId,
                truncateError(lastError)
        );
        return updatedRows == 1;
    }

    /**
     * 将审核消息或通知消息转换成 JSON。
     */
    private String serializePayload(
            Object message
    ) {
        try {
            return objectMapper
                    .writeValueAsString(message);

        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(
                    "Outbox 事件序列化失败",
                    exception
            );
        }
    }

    private void validatePayloadSize(String payload) {
        int payloadBytes = payload.getBytes(StandardCharsets.UTF_8).length;

        if (payloadBytes > MAX_PAYLOAD_BYTES) {
            throw new ContentFailedException(
                    "审核任务超过 32 KB，请缩短内容或图片地址"
            );
        }
    }

    private void validateImageUrls(List<String> imageUrls) {
        for (String imageUrl : imageUrls) {
            int urlBytes =
                    imageUrl.getBytes(StandardCharsets.UTF_8).length;

            if (urlBytes > MAX_IMAGE_URL_BYTES) {
                throw new ContentFailedException(
                        "单个图片地址不能超过 4096 字节"
                );
            }
        }
    }

    /**
     * 数据库 last_error 最大长度为 2000。
     */
    private String truncateError(String lastError) {
        if (lastError == null) {
            return "未知错误";
        }
        if (lastError.length() <= 2000) {
            return lastError;
        }
        return lastError.substring(0, 2000);
    }

    private String saveFeedEvent(Object message, String eventId, OutboxEventType eventType, Long contentId) {
        String payload = serializePayload(message);
        validatePayloadSize(payload);

        OutboxEvent event = OutboxEvent.builder()
                .eventId(eventId)
                .eventType(eventType.getCode())
                .aggregateType(ModerationTargetType.CONTENT.name())
                .aggregateId(contentId)
                .payload(payload)
                .status(OutboxEventStatus.PENDING.getCode())
                .retryCount(0)
                .nextRetryTime(LocalDateTime.now())
                .replayCount(0)
                .build();

        // Feed 事件失败必须抛异常，让帖子状态和 Outbox 一起回滚。
        if (outboxEventMapper.insert(event) != 1) {
            throw new ContentFailedException("创建 Feed 事件失败");
        }

        return eventId;
    }

    private void validateFeedContent(Content content) {
        if (content == null || content.getContentId() == null || content.getPublishUserId() == null || content.getContentType() == null) {
            throw new ContentFailedException("Feed 事件缺少帖子必要信息");
        }
    }
}
