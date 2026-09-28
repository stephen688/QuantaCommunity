package com.quanta.demo0.mq.producer;

import com.quanta.demo0.config.TopicTagMQConfig;
import com.quanta.demo0.mq.message.ContentTopicTagMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 内容主题标签失败转发生产者。
 *
 * 职责：把消费失败消息可靠转发到重试或死信交换机；
 * 边界：不负责首次发布，首次发布统一由 OutboxDispatcher 完成。
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class ContentTopicTagProducer {

    private final ReliableRabbitPublisher reliableRabbitPublisher;

    /** 将消息转发到延迟重试拓扑。 */
    public boolean sendRetryTask(ContentTopicTagMessage message) {
        return send(
                TopicTagMQConfig.TOPIC_TAG_RETRY_EXCHANGE,
                TopicTagMQConfig.TOPIC_TAG_RETRY_ROUTING_KEY,
                message,
                "主题标签消息进入重试队列");
    }

    /** 将消息转发到最终死信拓扑。 */
    public boolean sendDeadTask(ContentTopicTagMessage message) {
        return send(
                TopicTagMQConfig.TOPIC_TAG_DLX_EXCHANGE,
                TopicTagMQConfig.TOPIC_TAG_DLX_ROUTING_KEY,
                message,
                "主题标签消息进入死信队列");
    }

    private boolean send(String exchange, String routingKey, ContentTopicTagMessage message, String successMessage) {
        try {
            boolean sent = reliableRabbitPublisher.send(exchange, routingKey, message, message.getEventId());
            if (sent) {
                log.info("{}，eventId={}, retryCount={}", successMessage, message.getEventId(), message.getRetryCount());
            }
            return sent;
        } catch (Exception exception) {
            log.error("主题标签消息转发失败，eventId={}", message.getEventId(), exception);
            return false;
        }
    }
}
