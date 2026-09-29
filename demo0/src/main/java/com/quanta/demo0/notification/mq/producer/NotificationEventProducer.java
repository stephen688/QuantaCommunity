package com.quanta.demo0.notification.mq.producer;

import com.quanta.demo0.notification.mq.message.NotificationEventMessage;
import com.quanta.demo0.content.exception.ContentFailedException;
import com.quanta.demo0.platform.mq.exception.OutboxInsertFailedException;
import com.quanta.demo0.platform.mq.enums.OutboxEventType;
import com.quanta.demo0.platform.mq.producer.OutboxEventAppender;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 通知域可靠事件生产者：将通知消息包装成 Outbox 事件。
 */
@Service
@RequiredArgsConstructor
public class NotificationEventProducer {

    private final OutboxEventAppender outboxEventAppender;

    /**
     * 在业务事务中追加通知事件。
     */
    @Transactional
    public String createNotificationEvent(NotificationEventMessage message,
                                          String aggregateType, Long aggregateId) {
        if (message == null || aggregateType == null || aggregateId == null) {
            throw new IllegalArgumentException("通知 Outbox 事件缺少必要信息");
        }
        String eventId = UUID.randomUUID().toString();
        LocalDateTime occurredAt = LocalDateTime.now();
        message.setEventId(eventId);
        message.setEventType(OutboxEventType.NOTIFICATION_REQUESTED.getCode());
        message.setOccurredAt(occurredAt);
        message.setCreatedAt(occurredAt);
        message.setRetryCount(0);
        try {
            return outboxEventAppender.append(
                    eventId,
                    OutboxEventType.NOTIFICATION_REQUESTED.getCode(),
                    aggregateType,
                    aggregateId,
                    message);
        } catch (OutboxEventAppender.PayloadTooLargeException exception) {
            throw new ContentFailedException("审核任务超过 32 KB，请缩短内容或图片地址");
        } catch (OutboxInsertFailedException exception) {
            throw new IllegalStateException("创建通知 Outbox 事件失败");
        }
    }
}
