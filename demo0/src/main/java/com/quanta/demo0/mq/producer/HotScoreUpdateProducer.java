package com.quanta.demo0.mq.producer;

import com.quanta.demo0.config.RabbitMQConfig;
import com.quanta.demo0.mq.message.HotScoreMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class HotScoreUpdateProducer {
    @Autowired
    private ReliableRabbitPublisher reliableRabbitPublisher;


    /**
     * 发送热度消费重试消息，首次发送统一由 OutboxDispatcher 负责。
     */
    public boolean sendRetryTask(HotScoreMessage message) {
        try {
            if (!reliableRabbitPublisher.send(
                    RabbitMQConfig.HOT_SCORE_RETRY_EXCHANGE,
                    RabbitMQConfig.HOT_SCORE_RETRY_ROUTING_KEY,
                    message,
                    message.getEventId())) {
                return false;
            }
            log.info("发送热度重试消息成功，eventId={}, retryCount={}", message.getEventId(), message.getRetryCount());
            return true;
        } catch (Exception e) {
            log.error("发送热度重试消息失败，eventId={}", message.getEventId(), e);
            return false;
        }
    }

    /**
     * 将超过重试次数的热度消息发送到死信队列。
     */
    public boolean sendDeadTask(HotScoreMessage message) {
        try {
            if (!reliableRabbitPublisher.send(
                    RabbitMQConfig.HOT_SCORE_DLX_EXCHANGE,
                    RabbitMQConfig.HOT_SCORE_DLX_ROUTING_KEY,
                    message,
                    message.getEventId())) {
                return false;
            }
            log.error("热度消息进入死信队列，eventId={}", message.getEventId());
            return true;
        } catch (Exception e) {
            log.error("发送热度死信消息失败，eventId={}", message.getEventId(), e);
            return false;
        }
    }
}
