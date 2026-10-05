package com.quanta.demo0.notification.mq.producer;

import com.quanta.demo0.platform.mq.producer.ReliableRabbitPublisher;
import com.quanta.demo0.notification.config.NotificationMQConfig;
import com.quanta.demo0.notification.mq.message.NotificationEventMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * 通知推送消息生产者
 * 作用：负责发送通知消息到 RabbitMQ
 *
 * 补充说明：本类是"消费侧"转发生产者，只负责把消息转投重试/死信队列，不产生新通知。
 *
 * ============================================================
 * 【它和 NotificationEventProducer 是什么分工？】
 * ============================================================
 * 两个 Producer 各管一段，并存不是冗余：
 * - NotificationEventProducer 是"入口侧"：业务事务里把通知写进 Outbox 表，
 *   由 OutboxDispatcher 投到主队列，全项目业务域调用；
 * - 本类是"消费侧"：只被 NotificationConsumer 使用，消费失败转投 retry 队列、
 *   重试耗尽转投 DLX 队列，**从不往主队列发消息**（主队列消息只有 Outbox 一个来源）。
 *
 * 【为什么转发必须走确认发布？】
 * 转投动作发生在手动 ACK 之前：ReliableRabbitPublisher 等 broker 的
 * Confirm+Return 都成功才返回 true，失败时消费者会退回 basicNack 重新入队，
 * 消息不丢；若用普通 send（异步发完就忘），broker 没接住也无人知晓，
 * 消息在 ACK 之后凭空消失。
 */
@Service
@Slf4j
public class NotificationProducer {

    @Autowired
    private ReliableRabbitPublisher reliableRabbitPublisher;

    /**
     * 发送通知消费重试消息。
     *
     * 投到 retry 队列（x-message-ttl=60000）：消息躺满 60 秒后由 broker
     * 死信回主交换机，重新路由进主队列。返回 false 表示确认失败，
     * 调用方应 nackAndRequeue 而不是 ACK。
     */
    public boolean sendRetryTask(NotificationEventMessage message) {
        try {
            if (!reliableRabbitPublisher.send(
                    NotificationMQConfig.NOTIFICATION_RETRY_EXCHANGE,
                    NotificationMQConfig.NOTIFICATION_RETRY_ROUTING_KEY,
                    message,
                    message.getEventId())) {
                return false;
            }
            log.info("发送通知重试消息成功：eventId={}, retryCount={}", message.getEventId(), message.getRetryCount());
            return true;
        } catch (Exception e) {
            log.error("发送通知重试消息失败：eventId={}", message.getEventId(), e);
            return false;
        }
    }

    /**
     * 发送最终失败消息到通知死信队列。
     *
     * DLX 队列没有消费者，消息在这里停放保留现场（重试 3 次仍失败、
     * 或缺 eventId 无法记账），供人工排查后通过管理端重放 Inbox DEAD 事件。
     * 同样返回 false 表示确认失败，调用方应 nackAndRequeue。
     */
    public boolean sendDeadTask(NotificationEventMessage message) {
        try {
            if (!reliableRabbitPublisher.send(
                    NotificationMQConfig.NOTIFICATION_DLX_EXCHANGE,
                    NotificationMQConfig.NOTIFICATION_DLX_ROUTING_KEY,
                    message,
                    message.getEventId())) {
                return false;
            }
            log.error("通知消息进入死信队列：eventId={}", message.getEventId());
            return true;
        } catch (Exception e) {
            log.error("发送通知死信消息失败：eventId={}", message.getEventId(), e);
            return false;
        }
    }
}
