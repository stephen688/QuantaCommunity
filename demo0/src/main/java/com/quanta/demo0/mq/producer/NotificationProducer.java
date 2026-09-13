package com.quanta.demo0.mq.producer;

import com.quanta.demo0.config.RabbitMQConfig;
import com.quanta.demo0.mq.message.NotificationEventMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * 通知推送消息生产者
 * 作用：负责发送通知消息到 RabbitMQ
 */
@Service
@Slf4j
public class NotificationProducer {

    @Autowired
    private ReliableRabbitPublisher reliableRabbitPublisher;

    /**
     * 发送通知消费重试消息。
     */
    public boolean sendRetryTask(NotificationEventMessage message) {
        try {
            if (!reliableRabbitPublisher.send(
                    RabbitMQConfig.NOTIFICATION_RETRY_EXCHANGE,
                    RabbitMQConfig.NOTIFICATION_RETRY_ROUTING_KEY,
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
     */
    public boolean sendDeadTask(NotificationEventMessage message) {
        try {
            if (!reliableRabbitPublisher.send(
                    RabbitMQConfig.NOTIFICATION_DLX_EXCHANGE,
                    RabbitMQConfig.NOTIFICATION_DLX_ROUTING_KEY,
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
