package com.quanta.demo0.feed.mq.producer;

import com.quanta.demo0.content.exception.ContentFailedException;
import com.quanta.demo0.content.vo.ContentSnapshotVO;
import com.quanta.demo0.feed.mq.message.FeedDeleteMessage;
import com.quanta.demo0.feed.mq.message.HotScoreMessage;
import com.quanta.demo0.feed.mq.message.ProfileReconcileMessage;
import com.quanta.demo0.feed.mq.message.FeedPushMessage;
import com.quanta.demo0.feed.mq.message.UserBehaviorMessage;
import com.quanta.demo0.platform.mq.enums.OutboxEventType;
import com.quanta.demo0.platform.mq.exception.OutboxInsertFailedException;
import com.quanta.demo0.platform.mq.producer.OutboxEventAppender;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Feed 域可靠事件生产者：构造热度、用户行为和画像校准消息。
 *
 * 互动事实计数仍由同步 Service 调用完成；本类只发送 Feed/画像等派生副作用事件。
 */
@Service
@RequiredArgsConstructor
public class FeedEventProducer {

    private final OutboxEventAppender outboxEventAppender;

    /**
     * 点赞、收藏或评论数据变化后请求热度重算。
     */
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
                .eventTime(occurredAt)
                .build();
        try {
            return outboxEventAppender.append(
                    eventId,
                    OutboxEventType.HOT_SCORE_RECALCULATE_REQUESTED.getCode(),
                    "CONTENT",
                    contentId,
                    message);
        } catch (OutboxEventAppender.PayloadTooLargeException exception) {
            throw new ContentFailedException("审核任务超过 32 KB，请缩短内容或图片地址");
        } catch (OutboxInsertFailedException exception) {
            throw new ContentFailedException("创建热度重新计算事件失败");
        }
    }

    /**
     * 内容审核通过后创建 Feed upsert 事件。
     *
     * <p>Feed 只接收内容稳定快照，避免跨域依赖 content.entity.Content；消息字段和
     * Outbox 元数据保持原有语义。</p>
     */
    @Transactional
    public String createFeedUpsertEvent(ContentSnapshotVO content) {
        requireContent(content, "Feed 事件");
        String eventId = UUID.randomUUID().toString();
        LocalDateTime occurredAt = LocalDateTime.now();
        LocalDateTime contentCreateTime = content.getCreateTime() == null
                ? occurredAt : content.getCreateTime();
        Long createTime = contentCreateTime.atZone(java.time.ZoneId.systemDefault())
                .toInstant().toEpochMilli();
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
        try {
            return outboxEventAppender.append(
                    eventId,
                    OutboxEventType.FEED_UPSERT_REQUESTED.getCode(),
                    "CONTENT",
                    content.getContentId(),
                    message);
        } catch (OutboxEventAppender.PayloadTooLargeException exception) {
            throw new ContentFailedException("审核任务超过 32 KB，请缩短内容或图片地址");
        } catch (OutboxInsertFailedException exception) {
            throw new ContentFailedException("创建 Feed 事件失败");
        }
    }

    /**
     * 内容驳回或删除后创建 Feed delete 事件。
     */
    @Transactional
    public String createFeedDeleteEvent(ContentSnapshotVO content) {
        requireContent(content, "Feed 事件");
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
        try {
            return outboxEventAppender.append(
                    eventId,
                    OutboxEventType.FEED_DELETE_REQUESTED.getCode(),
                    "CONTENT",
                    content.getContentId(),
                    message);
        } catch (OutboxEventAppender.PayloadTooLargeException exception) {
            throw new ContentFailedException("审核任务超过 32 KB，请缩短内容或图片地址");
        } catch (OutboxInsertFailedException exception) {
            throw new ContentFailedException("创建 Feed 事件失败");
        }
    }

    /**
     * 创建用户行为画像更新事件。
     */
    @Transactional
    public String createUserBehaviorEvent(Long userId, Long contentId, String behaviorType) {
        return createUserBehaviorEvent(userId, contentId, behaviorType, null);
    }

    /**
     * 创建用户行为画像更新事件，允许浏览对账任务传入稳定 eventId。
     */
    @Transactional
    public String createUserBehaviorEvent(Long userId, Long contentId, String behaviorType, String eventId) {
        if (userId == null || contentId == null) {
            throw new ContentFailedException("用户行为事件缺少用户 ID 或帖子 ID");
        }
        validateBehaviorType(behaviorType);

        String finalEventId = eventId == null ? UUID.randomUUID().toString() : eventId;
        LocalDateTime occurredAt = LocalDateTime.now();
        UserBehaviorMessage message = UserBehaviorMessage.builder()
                .eventId(finalEventId)
                .eventType(OutboxEventType.USER_BEHAVIOR_REQUESTED.getCode())
                .occurredAt(occurredAt)
                .userId(userId)
                .contentId(contentId)
                .behaviorType(behaviorType)
                .retryCount(0)
                .build();
        try {
            return outboxEventAppender.append(
                    finalEventId,
                    OutboxEventType.USER_BEHAVIOR_REQUESTED.getCode(),
                    "USER",
                    userId,
                    message);
        } catch (OutboxEventAppender.PayloadTooLargeException exception) {
            throw new ContentFailedException("审核任务超过 32 KB，请缩短内容或图片地址");
        } catch (OutboxInsertFailedException exception) {
            throw new ContentFailedException("创建用户行为事件失败");
        }
    }

    /**
     * 保存显式画像校准事件，并保留调用方传入的稳定 eventId。
     */
    @Transactional
    public String createProfileUpdatedEvent(Long userId, String eventId) {
        if (userId == null || userId <= 0 || !StringUtils.hasText(eventId)) {
            throw new ContentFailedException("显式画像事件缺少必要标识");
        }
        ProfileReconcileMessage message = ProfileReconcileMessage.builder()
                .eventId(eventId)
                .eventType(OutboxEventType.USER_PROFILE_UPDATED.getCode())
                .userId(userId)
                .occurredAt(LocalDateTime.now())
                .retryCount(0)
                .build();
        try {
            return outboxEventAppender.append(
                    eventId,
                    OutboxEventType.USER_PROFILE_UPDATED.getCode(),
                    "USER",
                    userId,
                    message);
        } catch (OutboxEventAppender.PayloadTooLargeException exception) {
            throw new ContentFailedException("审核任务超过 32 KB，请缩短内容或图片地址");
        } catch (OutboxInsertFailedException exception) {
            throw new ContentFailedException("推荐异步事件写入失败");
        }
    }

    private void validateBehaviorType(String behaviorType) {
        if (behaviorType == null
                || !List.of("LIKE", "COLLECT", "COMMENT", "VIEW").contains(behaviorType)) {
            throw new ContentFailedException("用户行为类型非法：" + behaviorType);
        }
    }

    private void requireContent(ContentSnapshotVO content, String eventName) {
        if (content == null
                || content.getContentId() == null
                || content.getPublishUserId() == null
                || content.getContentType() == null) {
            throw new ContentFailedException(eventName + "缺少帖子必要信息");
        }
    }
}
