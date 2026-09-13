package com.quanta.demo0.mq.producer;

import com.quanta.demo0.config.RabbitMQConfig;
import com.quanta.demo0.mq.message.FeedPushMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Feed 流推送消息生产者
 * 作用：负责发送消息到 RabbitMQ
 */
@Service
@Slf4j
public class FeedPushProducer {

    @Autowired
    private ReliableRabbitPublisher reliableRabbitPublisher;

    /**
     * 发送 Feed 消费重试消息，首次发送统一由 OutboxDispatcher 负责。
     */
    public boolean sendRetryTask(FeedPushMessage message) {
        try {
            if (!reliableRabbitPublisher.send(
                    RabbitMQConfig.FEED_PUSH_RETRY_EXCHANGE,
                    RabbitMQConfig.FEED_PUSH_RETRY_ROUTING_KEY,
                    message,
                    message.getEventId())) {
                return false;
            }
            log.info("发送 Feed 重试消息成功，eventId={}, retryCount={}", message.getEventId(), message.getRetryCount());
            return true;
        } catch (Exception e) {
            log.error("发送 Feed 重试消息失败，eventId={}", message.getEventId(), e);
            return false;
        }
    }

    public boolean sendDeadTask(FeedPushMessage message) {
        try {
            if (!reliableRabbitPublisher.send(
                    RabbitMQConfig.FEED_PUSH_DLX_EXCHANGE,
                    RabbitMQConfig.FEED_PUSH_DLX_ROUTING_KEY,
                    message,
                    message.getEventId())) {
                return false;
            }
            log.error("Feed 消息进入死信队列，eventId={}", message.getEventId());
            return true;
        } catch (Exception e) {
            log.error("发送 Feed 死信消息失败，eventId={}", message.getEventId(), e);
            return false;
        }
    }
}
