package com.quanta.demo0.platform.mq.config;

import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * RabbitMQ 共享基础配置。
 *
 * 职责：提供消息转换器和 RabbitTemplate 的公共装配；
 * 边界：领域队列、交换机和绑定由各领域 MQ 配置声明。
 */
@Configuration
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
            if (!ack) {
                System.err.println("消息发送失败：" + cause);
            }
        });
        rabbitTemplate.setMandatory(true);
        rabbitTemplate.setReturnsCallback(returned -> {
            System.err.println("消息路由失败：" + returned);
        });
        return rabbitTemplate;
    }
}
