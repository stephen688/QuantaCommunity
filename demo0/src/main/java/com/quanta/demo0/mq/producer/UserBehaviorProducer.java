package com.quanta.demo0.mq.producer;

import com.quanta.demo0.config.RabbitMQConfig;
import com.quanta.demo0.mq.message.UserBehaviorMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 用户行为画像消息辅助生产者（推荐流个性化 D2）。
 * 职责：画像消费者消费失败后，向重试/死信拓扑转发消息。
 * 边界：首次发送统一由 OutboxDispatcher 负责，本类不承担首发。
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class UserBehaviorProducer {

    private final ReliableRabbitPublisher reliableRabbitPublisher;

    /**
     * 发送画像消费重试消息，首次发送统一由 OutboxDispatcher 负责。
     */
    public boolean sendRetryTask(UserBehaviorMessage message) {
        try {
            if (!reliableRabbitPublisher.send(
                    RabbitMQConfig.USER_BEHAVIOR_RETRY_EXCHANGE,
                    RabbitMQConfig.USER_BEHAVIOR_RETRY_ROUTING_KEY,
                    message,
                    message.getEventId())) {
                return false;
            }
            log.info("发送用户行为重试消息成功，eventId={}, retryCount={}", message.getEventId(), message.getRetryCount());
            return true;
        } catch (Exception e) {
            log.error("发送用户行为重试消息失败，eventId={}", message.getEventId(), e);
            return false;
        }
    }

    /**
     * 将超过重试次数的用户行为消息发送到死信队列。
     */
    public boolean sendDeadTask(UserBehaviorMessage message) {
        try {
            if (!reliableRabbitPublisher.send(
                    RabbitMQConfig.USER_BEHAVIOR_DLX_EXCHANGE,
                    RabbitMQConfig.USER_BEHAVIOR_DLX_ROUTING_KEY,
                    message,
                    message.getEventId())) {
                return false;
            }
            log.error("用户行为消息进入死信队列，eventId={}", message.getEventId());
            return true;
        } catch (Exception e) {
            log.error("发送用户行为死信消息失败，eventId={}", message.getEventId(), e);
            return false;
        }
    }
}
