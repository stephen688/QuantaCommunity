package com.quanta.demo0.platform.mq.config;

import com.quanta.demo0.platform.mq.trace.RabbitTraceAdvice;
import com.quanta.demo0.platform.web.trace.TraceContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.boot.autoconfigure.amqp.SimpleRabbitListenerContainerFactoryConfigurer;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.charset.StandardCharsets;

/**
 * RabbitMQ 共享基础配置。
 *
 * 职责：提供消息转换器和 RabbitTemplate 的公共装配；
 * 边界：领域队列、交换机和绑定由各领域 MQ 配置声明。
 */
@Configuration
@Slf4j
public class RabbitMQConfig {

    /**
     * 消息转换器（JSON 格式）。
     *
     * @return RabbitMQ JSON 消息转换器
     */
    @Bean
    public MessageConverter messageConverter() {
        return new Jackson2JsonMessageConverter();
    }

    /**
     * RabbitTemplate 配置。
     *
     * @param connectionFactory RabbitMQ 连接工厂
     * @return 配置了确认、返回和 JSON 转换器的模板
     */
    @Bean
    public RabbitTemplate rabbitTemplate(ConnectionFactory connectionFactory) {
        RabbitTemplate rabbitTemplate = new RabbitTemplate(connectionFactory);
        rabbitTemplate.setMessageConverter(messageConverter());
        rabbitTemplate.setConfirmCallback((correlationData, ack, cause) -> {
            log.info("rabbitmq_publish_confirm correlationId={}, ack={}",
                    correlationData == null ? null : correlationData.getId(),
                    ack);
        });
        rabbitTemplate.setMandatory(true);
        rabbitTemplate.setReturnsCallback(returned -> {
            Message message = returned.getMessage();
            String eventId = validEventId(headerValue(message, TraceContext.EVENT_ID_HEADER));
            String traceId = TraceContext.resolveEvent(
                    headerValue(message, TraceContext.REQUEST_ID_HEADER),
                    eventId
            );
            try (TraceContext.Scope ignored = TraceContext.open(traceId, eventId)) {
                log.warn("rabbitmq_message_returned exchange={}, routingKey={}, replyCode={}, eventId={}",
                        returned.getExchange(),
                        returned.getRoutingKey(),
                        returned.getReplyCode(),
                        eventId);
            }
        });
        return rabbitTemplate;
    }

    /**
     * 复用 Spring Boot 已绑定的 listener 属性，再追加一次公共 trace advice，
     * 避免手写 ACK、prefetch 等参数导致与 application.yml 分叉。
     */
    @Bean
    public SimpleRabbitListenerContainerFactory rabbitListenerContainerFactory(
            SimpleRabbitListenerContainerFactoryConfigurer configurer,
            ConnectionFactory connectionFactory,
            RabbitTraceAdvice rabbitTraceAdvice
    ) {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        configurer.configure(factory, connectionFactory);
        factory.setAdviceChain(rabbitTraceAdvice);
        return factory;
    }

    private static String headerValue(Message message, String name) {
        if (message == null || message.getMessageProperties() == null) {
            return null;
        }
        Object value = message.getMessageProperties().getHeaders().get(name);
        if (value instanceof byte[] bytes && bytes.length <= 128) {
            return new String(bytes, StandardCharsets.US_ASCII);
        }
        if (value instanceof String text && text.length() <= 128) {
            return text;
        }
        return null;
    }

    private static String validEventId(String candidate) {
        return TraceContext.isValidEventId(candidate) ? candidate : null;
    }
}
