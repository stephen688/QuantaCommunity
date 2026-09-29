package com.quanta.demo0.comment.mq.producer;

import com.quanta.demo0.comment.entity.ContentComment;
import com.quanta.demo0.comment.exception.CommentFailedException;
import com.quanta.demo0.content.exception.ContentFailedException;
import com.quanta.demo0.comment.mq.message.BotMentionMessage;
import com.quanta.demo0.moderation.enums.ModerationTargetType;
import com.quanta.demo0.moderation.mq.message.ModerationTaskMessage;
import com.quanta.demo0.platform.mq.enums.OutboxEventType;
import com.quanta.demo0.platform.mq.exception.OutboxInsertFailedException;
import com.quanta.demo0.platform.mq.producer.OutboxEventAppender;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 评论域可靠事件生产者：构造评论审核和 Bot 触发消息。
 */
@Service
@RequiredArgsConstructor
public class CommentEventProducer {

    private static final int MAX_IMAGE_URL_BYTES = 4096;

    private final OutboxEventAppender outboxEventAppender;

    /**
     * 在评论发布事务中创建审核事件。
     */
    @Transactional
    public String createCommentModerationEvent(ContentComment comment, List<String> imageUrls) {
        requireComment(comment);
        List<String> safeImageUrls = imageUrls == null ? List.of() : List.copyOf(imageUrls);
        validateImageUrls(safeImageUrls);

        String eventId = UUID.randomUUID().toString();
        LocalDateTime occurredAt = LocalDateTime.now();
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
        try {
            return outboxEventAppender.append(
                    eventId,
                    OutboxEventType.MODERATION_REQUESTED.getCode(),
                    ModerationTargetType.COMMENT.name(),
                    comment.getCommentId(),
                    message);
        } catch (OutboxEventAppender.PayloadTooLargeException exception) {
            throw new ContentFailedException("审核任务超过 32 KB，请缩短内容或图片地址");
        } catch (OutboxInsertFailedException exception) {
            throw new CommentFailedException("创建评论审核任务失败");
        }
    }

    /**
     * 在审核通过的评论事务中创建 Bot 触发事件。
     */
    @Transactional
    public String createBotMentionEvent(ContentComment comment, List<String> imageUrls,
                                        String botTriggerKind) {
        requireComment(comment);
        if (!"mentioned".equals(botTriggerKind) && !"replied".equals(botTriggerKind)) {
            throw new CommentFailedException("bot 触发类型非法");
        }
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
        try {
            return outboxEventAppender.append(
                    eventId,
                    OutboxEventType.BOT_MENTION_REQUESTED.getCode(),
                    ModerationTargetType.COMMENT.name(),
                    comment.getCommentId(),
                    message);
        } catch (OutboxEventAppender.PayloadTooLargeException exception) {
            throw new ContentFailedException("审核任务超过 32 KB，请缩短内容或图片地址");
        } catch (OutboxInsertFailedException exception) {
            throw new CommentFailedException("创建 bot 触发事件失败");
        }
    }

    private void requireComment(ContentComment comment) {
        if (comment == null || comment.getCommentId() == null || comment.getUserId() == null) {
            throw new CommentFailedException("评论事件缺少必要信息");
        }
    }

    private void validateImageUrls(List<String> imageUrls) {
        for (String imageUrl : imageUrls) {
            if (imageUrl != null
                    && imageUrl.getBytes(StandardCharsets.UTF_8).length > MAX_IMAGE_URL_BYTES) {
                throw new ContentFailedException("单个图片地址不能超过 4096 字节");
            }
        }
    }
}
