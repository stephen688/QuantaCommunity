package com.quanta.demo0.mq.producer;

import com.quanta.demo0.properties.OutboxDispatchProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * 消费者重试和死信消息的确认发布器。
 * 只有 RabbitMQ ACK 且消息没有被 Return 时才返回成功。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReliableRabbitPublisher {

    private final RabbitTemplate rabbitTemplate;

    private final OutboxDispatchProperties outboxDispatchProperties;

    public boolean send(
            String exchange,
            String routingKey,
            Object message,
            String eventId
    ) {
        CorrelationData correlationData = new CorrelationData(
                eventId + "-" + UUID.randomUUID()
        );

        try {
            rabbitTemplate.convertAndSend(
                    exchange,
                    routingKey,
                    message,
                    correlationData
            );

            CorrelationData.Confirm confirm = correlationData.getFuture().get(
                    outboxDispatchProperties.getConfirmTimeoutSeconds(),
                    TimeUnit.SECONDS
            );

            if (!confirm.isAck()) {
                log.error("RabbitMQ 发布 NACK，eventId={}, exchange={}, routingKey={}, reason={}",
                        eventId, exchange, routingKey, confirm.getReason());
                return false;
            }

            if (correlationData.getReturned() != null) {
                log.error("RabbitMQ 消息未路由，eventId={}, exchange={}, routingKey={}, replyText={}",
                        eventId, exchange, routingKey, correlationData.getReturned().getReplyText());
                return false;
            }

            return true;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            log.error("等待 RabbitMQ 发布确认时线程被中断，eventId={}", eventId, exception);
            return false;
        } catch (Exception exception) {
            log.error("RabbitMQ 确认发布失败，eventId={}, exchange={}, routingKey={}",
                    eventId, exchange, routingKey, exception);
            return false;
        }
    }
}
