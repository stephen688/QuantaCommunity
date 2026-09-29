package com.quanta.demo0.answer.mq.producer;

import com.quanta.demo0.answer.entity.QuestionAnswer;
import com.quanta.demo0.content.exception.ContentFailedException;
import com.quanta.demo0.moderation.enums.ModerationTargetType;
import com.quanta.demo0.moderation.mq.message.ModerationTaskMessage;
import com.quanta.demo0.platform.mq.enums.OutboxEventType;
import com.quanta.demo0.platform.mq.exception.OutboxInsertFailedException;
import com.quanta.demo0.platform.mq.producer.OutboxEventAppender;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 回答域可靠事件生产者：构造回答审核消息并追加到通用 Outbox。
 */
@Service
@RequiredArgsConstructor
public class AnswerEventProducer {

    private final OutboxEventAppender outboxEventAppender;

    /**
     * 在回答发布事务中创建审核事件。
     */
    @Transactional
    public String createAnswerModerationEvent(QuestionAnswer answer) {
        if (answer == null || answer.getAnswerId() == null || answer.getUserId() == null) {
            throw new ContentFailedException("创建回答审核任务缺少必要信息");
        }

        String eventId = UUID.randomUUID().toString();
        LocalDateTime occurredAt = LocalDateTime.now();
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
        try {
            return outboxEventAppender.append(
                    eventId,
                    OutboxEventType.MODERATION_REQUESTED.getCode(),
                    ModerationTargetType.ANSWER.name(),
                    answer.getAnswerId(),
                    message);
        } catch (OutboxEventAppender.PayloadTooLargeException exception) {
            throw new ContentFailedException("审核任务超过 32 KB，请缩短内容或图片地址");
        } catch (OutboxInsertFailedException exception) {
            throw new ContentFailedException("创建回答审核任务失败");
        }
    }
}
