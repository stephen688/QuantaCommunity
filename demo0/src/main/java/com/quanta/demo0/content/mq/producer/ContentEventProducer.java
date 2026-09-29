package com.quanta.demo0.content.mq.producer;

import com.quanta.demo0.content.entity.Content;
import com.quanta.demo0.content.exception.ContentFailedException;
import com.quanta.demo0.content.mq.message.ContentTopicTagMessage;
import com.quanta.demo0.content.vo.ContentSnapshotVO;
import com.quanta.demo0.feed.mq.producer.FeedEventProducer;
import com.quanta.demo0.moderation.enums.ModerationTargetType;
import com.quanta.demo0.moderation.mq.message.ModerationTaskMessage;
import com.quanta.demo0.platform.mq.enums.OutboxEventType;
import com.quanta.demo0.platform.mq.producer.OutboxEventAppender;
import com.quanta.demo0.platform.mq.exception.OutboxInsertFailedException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 内容域可靠事件生产者：构造内容审核、Feed 校准和主题标签消息。
 *
 * 边界：只读取内容域实体并调用 platform/mq 通用追加能力，不直接访问 Outbox Mapper。
 */
@Service
@RequiredArgsConstructor
public class ContentEventProducer {

    private static final int MAX_IMAGE_URL_BYTES = 4096;

    private final OutboxEventAppender outboxEventAppender;
    private final FeedEventProducer feedEventProducer;

    /**
     * 在内容发布事务中创建审核事件。
     */
    @Transactional
    public String createContentModerationEvent(Content content, List<String> imageUrls) {
        List<String> safeImageUrls = imageUrls == null ? List.of() : List.copyOf(imageUrls);
        validateImageUrls(safeImageUrls);
        requireContent(content, "审核事件");

        String eventId = UUID.randomUUID().toString();
        LocalDateTime occurredAt = LocalDateTime.now();
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
        try {
            return outboxEventAppender.append(
                    eventId,
                    OutboxEventType.MODERATION_REQUESTED.getCode(),
                    ModerationTargetType.CONTENT.name(),
                    content.getContentId(),
                    message);
        } catch (OutboxEventAppender.PayloadTooLargeException exception) {
            throw payloadTooLarge(exception);
        } catch (OutboxInsertFailedException exception) {
            throw new ContentFailedException("创建帖子审核任务失败");
        }
    }

    /**
     * 帖子审核通过时创建 Feed 校准事件。
     */
    @Transactional
    public String createFeedUpsertEvent(Content content) {
        requireContent(content, "Feed 事件");
        return feedEventProducer.createFeedUpsertEvent(toSnapshot(content));
    }

    /**
     * 帖子驳回或删除时创建 Feed 校准事件。
     */
    @Transactional
    public String createFeedDeleteEvent(Content content) {
        requireContent(content, "Feed 事件");
        return feedEventProducer.createFeedDeleteEvent(toSnapshot(content));
    }

    /**
     * 为指定内容登记稳定的主题标签任务。
     */
    @Transactional
    public String createContentTopicTagEvent(Long contentId) {
        if (contentId == null || contentId <= 0) {
            throw new ContentFailedException("主题打标缺少有效帖子 ID");
        }
        String eventId = UUID.nameUUIDFromBytes(
                ("content-topic-v1:" + contentId).getBytes(StandardCharsets.UTF_8)).toString();
        ContentTopicTagMessage message = ContentTopicTagMessage.builder()
                .eventId(eventId)
                .eventType(OutboxEventType.CONTENT_TOPIC_TAG_REQUESTED.getCode())
                .contentId(contentId)
                .occurredAt(LocalDateTime.now())
                .retryCount(0)
                .build();
        try {
            return outboxEventAppender.appendIfAbsent(
                    eventId,
                    OutboxEventType.CONTENT_TOPIC_TAG_REQUESTED.getCode(),
                    ModerationTargetType.CONTENT.name(),
                    contentId,
                    message);
        } catch (OutboxEventAppender.PayloadTooLargeException exception) {
            throw payloadTooLarge(exception);
        }
    }

    private void requireContent(Content content, String eventName) {
        if (content == null
                || content.getContentId() == null
                || content.getPublishUserId() == null
                || content.getContentType() == null) {
            throw new ContentFailedException(eventName + "缺少帖子必要信息");
        }
    }

    private ContentSnapshotVO toSnapshot(Content content) {
        return ContentSnapshotVO.builder()
                .contentId(content.getContentId())
                .contentType(content.getContentType())
                .title(content.getTitle())
                .content(content.getContent())
                .tags(content.getTags())
                .publishUserId(content.getPublishUserId())
                .auditStatus(content.getAuditStatus())
                .isDeleted(content.getIsDeleted())
                .createTime(content.getCreateTime())
                .updateTime(content.getUpdateTime())
                .likedCount(content.getLiked())
                .commentCount(content.getCommentCount())
                .collectCount(content.getCollectCount())
                .build();
    }

    private void validateImageUrls(List<String> imageUrls) {
        for (String imageUrl : imageUrls) {
            if (imageUrl != null
                    && imageUrl.getBytes(StandardCharsets.UTF_8).length > MAX_IMAGE_URL_BYTES) {
                throw new ContentFailedException("单个图片地址不能超过 4096 字节");
            }
        }
    }

    private ContentFailedException payloadTooLarge(OutboxEventAppender.PayloadTooLargeException exception) {
        return new ContentFailedException("审核任务超过 32 KB，请缩短内容或图片地址");
    }
}
