package com.quanta.demo0.platform.mq.producer;

import com.quanta.demo0.platform.mq.properties.OutboxDispatchProperties;
import com.quanta.demo0.platform.web.trace.TraceContext;
import org.springframework.amqp.core.MessagePostProcessor;
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
        String traceId = TraceContext.resolveEvent(
                TraceContext.currentTraceId(),
                eventId
        );

        try (TraceContext.Scope ignored = TraceContext.open(traceId, eventId)) {
            CorrelationData correlationData = new CorrelationData(
                    eventId + "-" + UUID.randomUUID()
            );

            rabbitTemplate.convertAndSend(
                    exchange,
                    routingKey,
                    message,
                    traceHeaders(eventId),
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

    /**
     * 为重试或死信消息附加关联元数据。消息体契约保持不变，旧消费者会忽略未知头。
     */
    private MessagePostProcessor traceHeaders(String eventId) {
        return rabbitMessage -> {
            rabbitMessage.getMessageProperties().setHeader(
                    TraceContext.REQUEST_ID_HEADER,
                    TraceContext.currentTraceId()
            );
            if (eventId != null && eventId.matches("[A-Za-z0-9_.:-]{1,128}")) {
                rabbitMessage.getMessageProperties().setHeader(
                        TraceContext.EVENT_ID_HEADER,
                        eventId
                );
            }
            return rabbitMessage;
        };
    }
}
