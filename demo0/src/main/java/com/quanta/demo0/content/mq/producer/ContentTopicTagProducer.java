package com.quanta.demo0.content.mq.producer;

import com.quanta.demo0.platform.mq.producer.ReliableRabbitPublisher;
import com.quanta.demo0.content.config.TopicTagMQConfig;
import com.quanta.demo0.content.mq.message.ContentTopicTagMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 内容主题标签失败转发生产者。
 *
 * 职责：把消费失败消息可靠转发到重试或死信交换机；
 * 边界：不负责首次发布，首次发布统一由 OutboxDispatcher 完成。
 *
 * ============================================================
 * 【消费端转发为什么也要"可靠发布"？—— 失败分支必须闭环】
 * ============================================================
 * 消费者抢占 BUSY、消费失败重试、重试超限判死时，都要把消息转投到
 * 对应的交换机。转发走 ReliableRabbitPublisher：publisher-confirm 确认
 * broker 已收 + returned 检查确认确实路由到了队列，两关都过才算成功。
 * 返回 false 或抛异常时，消费者会 basicNack(requeue=true) 把**原消息**退回，
 * 下次投递重新走 Inbox 状态机 —— **转发失败绝不等于任务丢失，
 * 只是"这次没送出去"，原消息仍在 Rabbit 手里**。
 *
 * 对比反例：如果这里用裸 rabbitTemplate（fire-and-forget），
 * 转发丢了却还 ACK 了原消息，打标任务就静默蒸发 ——
 * **重试链路的第一原则：宁可不投，不可丢投**。
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class ContentTopicTagProducer {

    private final ReliableRabbitPublisher reliableRabbitPublisher;

    /** 将消息转发到延迟重试拓扑。 */
    // 【去向】RETRY_EXCHANGE → retry 队列躺 60s（x-message-ttl）→ 死信回主交换机重投。
    // 两个用途：消费失败的定时重试（retryCount 已 +1）、抢占 BUSY 时的"稍后再来"。
    public boolean sendRetryTask(ContentTopicTagMessage message) {
        return send(
                TopicTagMQConfig.TOPIC_TAG_RETRY_EXCHANGE,
                TopicTagMQConfig.TOPIC_TAG_RETRY_ROUTING_KEY,
                message,
                "主题标签消息进入重试队列");
    }

    /** 将消息转发到最终死信拓扑。 */
    // 【去向】DLX_EXCHANGE → dlx 队列留档（无消费者、无 TTL），等运营排查后重放。
    // 【与 sendRetryTask 的区别】重试 = 还想自动做成；死信 = 承认自动做不成，转人工。
    public boolean sendDeadTask(ContentTopicTagMessage message) {
        return send(
                TopicTagMQConfig.TOPIC_TAG_DLX_EXCHANGE,
                TopicTagMQConfig.TOPIC_TAG_DLX_ROUTING_KEY,
                message,
                "主题标签消息进入死信队列");
    }

    /**
     * 统一转发入口：确认式发布，任何异常都收敛为 false。
     * 【返回值契约】true = broker 已确认接收且成功路由入队，调用方可放心 ACK 原消息；
     * false = 无法确认送达，调用方必须 NACK requeue 保住原消息（见消费者的 nackAndRequeue）。
     */
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
