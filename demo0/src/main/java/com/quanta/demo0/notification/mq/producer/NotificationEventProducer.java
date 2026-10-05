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
 *
 * 这是通知链路唯一的"入口侧"生产者，被审核、点赞、关注、举报、认证等
 * 十余个业务域调用（answer/content/comment/identity/follow/interaction 包内的
 * *ServiceImpl）。它不直接连 RabbitMQ——只往 tb_outbox_event 表插一行。
 *
 * ============================================================
 * 【为什么发通知要写 Outbox 表，而不是直接 rabbitTemplate 发 MQ？】
 * ============================================================
 * 业务数据（如审核结果、点赞关系）和 MQ 消息是两个存储，单个数据库事务
 * 无法同时罩住：先发 MQ 再提交业务事务，事务一旦回滚，通知就"凭空出现"；
 * 先提交业务再发 MQ，应用在两步之间崩溃，通知就永久丢失。
 * Outbox 模式把消息当业务表的一行，在本事务里一起提交：**事务成功则事件必在**，
 * 之后由 OutboxDispatcher 定时（每 1 秒）扫表投递，投递失败自行按退避重试。
 */
@Service
@RequiredArgsConstructor
public class NotificationEventProducer {

    private final OutboxEventAppender outboxEventAppender;

    /**
     * 在业务事务中追加通知事件。
     *
     * 【必须在调用方的事务里跑】@Transactional 默认 REQUIRED 传播，方法加入
     * 调用方当前事务；append 也不自开事务——这样事件和业务数据才能原子提交。
     * 若调用方没有事务，这里会自开一个（仅包含本插入），可靠性退化为
     * "事件至少写进 Outbox"，但业务与通知不再原子，生产方应始终带着事务调用。
     *
     * 【eventId 是整条链路的幂等凭据】在此生成（UUID）后随消息走：
     * Outbox 表、RabbitMQ 消息体、Inbox 表的记账全靠它对账。
     *
     * 【异常翻译】payload 超过 OutboxEventAppender 的 32 KB 上限（MAX_PAYLOAD_BYTES）
     * 会抛 PayloadTooLargeException，这里转成 ContentFailedException 让业务方
     * 拿到可读的业务错误，而不是平台层的序列化异常。
     */
    @Transactional
    public String createNotificationEvent(NotificationEventMessage message,
                                          String aggregateType, Long aggregateId) {
        if (message == null || aggregateType == null || aggregateId == null) {
            throw new IllegalArgumentException("通知 Outbox 事件缺少必要信息");
        }
        // 消息补全元数据：eventId 幂等凭据、eventType 固定为 NOTIFICATION_REQUESTED、
        // retryCount 归零（消费侧重试从 0 开始计数）
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
