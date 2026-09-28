package com.quanta.demo0.feed.mq.producer;

import com.quanta.demo0.platform.mq.producer.ReliableRabbitPublisher;
import com.quanta.demo0.feed.config.ProfileMQConfig;
import com.quanta.demo0.feed.mq.message.ProfileReconcileMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/** 显式画像失败转发：首次发送统一 Outbox，只提供 Confirm/Return 可确认的重试与 DLQ。 */
@Component @RequiredArgsConstructor @Slf4j
public class ProfileReconcileProducer {
    private final ReliableRabbitPublisher publisher;
    /** 重试转发确认失败返回 false，由消费者 NACK 重入原队列。 */
    public boolean sendRetryTask(ProfileReconcileMessage message) {
        return send(ProfileMQConfig.PROFILE_RETRY_EXCHANGE, ProfileMQConfig.PROFILE_RETRY_ROUTING_KEY, message);
    }
    /** 超过上限或非法消息可靠转入死信，不能先 ACK 再尽力发送。 */
    public boolean sendDeadTask(ProfileReconcileMessage message) {
        return send(ProfileMQConfig.PROFILE_DLX_EXCHANGE, ProfileMQConfig.PROFILE_DLX_ROUTING_KEY, message);
    }
    private boolean send(String exchange, String routingKey, ProfileReconcileMessage message) {
        try {
            return publisher.send(exchange, routingKey, message, message.getEventId());
        } catch (Exception exception) {
            log.error("画像消息转发失败，eventId={}", message.getEventId(), exception);
            return false;
        }
    }
}
