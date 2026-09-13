package com.quanta.demo0.mq.producer;

import com.quanta.demo0.config.RabbitMQConfig;
import com.quanta.demo0.mq.message.SearchReconcileMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * ES 校准消费者辅助生产者。
 *
 * 注意：
 * 第一次发送由 OutboxDispatcher 负责；
 * 这个 Producer 只负责消费者失败后的重试和死信。
 */
@Component
@Slf4j
public class SearchReconcileProducer {

    @Autowired
    private ReliableRabbitPublisher reliableRabbitPublisher;

    /**
     * 发送 ES 校准重试消息。
     */
    public boolean sendRetryTask(SearchReconcileMessage message) {
        try {
            if (!reliableRabbitPublisher.send(
                    RabbitMQConfig.SEARCH_RECONCILE_RETRY_EXCHANGE,
                    RabbitMQConfig.SEARCH_RECONCILE_RETRY_ROUTING_KEY,
                    message,
                    message.getEventId())) {
                return false;
            }

            log.info("发送 ES 校准重试消息成功，eventId={}, retryCount={}", message.getEventId(), message.getRetryCount());
            return true;
        } catch (Exception e) {
            log.error("发送 ES 校准重试消息失败，eventId={}", message.getEventId(), e);
            return false;
        }
    }

    /**
     * 将超过重试次数的消息发送到死信队列。
     */
    public boolean sendDeadTask(SearchReconcileMessage message) {
        try {
            if (!reliableRabbitPublisher.send(
                    RabbitMQConfig.SEARCH_RECONCILE_DLX_EXCHANGE,
                    RabbitMQConfig.SEARCH_RECONCILE_DLX_ROUTING_KEY,
                    message,
                    message.getEventId())) {
                return false;
            }

            log.error("ES 校准消息进入死信队列，eventId={}", message.getEventId());
            return true;
        } catch (Exception e) {
            log.error("发送 ES 校准死信消息失败，eventId={}", message.getEventId(), e);
            return false;
        }
    }
}
