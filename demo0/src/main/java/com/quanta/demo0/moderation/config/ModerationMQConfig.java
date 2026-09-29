package com.quanta.demo0.moderation.config;

import org.springframework.amqp.core.*;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 内容审核 RabbitMQ 拓扑。
 *
 * 职责：只声明该领域 RabbitMQ 队列、交换机和绑定；
 * 边界：消息发送与可靠投递由领域 Producer 和 platform/mq 负责。
 */
@Configuration
public class ModerationMQConfig {

    public static final String MODERATION_QUEUE = "moderation.queue";
    public static final String MODERATION_EXCHANGE = "moderation.exchange";
    public static final String MODERATION_ROUTING_KEY = "moderation.task";

    public static final String MODERATION_RETRY_QUEUE = "moderation.retry.queue";
    public static final String MODERATION_RETRY_EXCHANGE = "moderation.retry.exchange";
    public static final String MODERATION_RETRY_ROUTING_KEY = "moderation.retry";

    public static final String MODERATION_DLX_QUEUE = "moderation.dlx.queue";
    public static final String MODERATION_DLX_EXCHANGE = "moderation.dlx.exchange";
    public static final String MODERATION_DLX_ROUTING_KEY = "moderation.dlx";

    // 审核主队列
    @Bean
    public Queue moderationQueue() {
        return QueueBuilder.durable(MODERATION_QUEUE)
                .withArgument("x-dead-letter-exchange", MODERATION_DLX_EXCHANGE)
                .withArgument("x-dead-letter-routing-key", MODERATION_DLX_ROUTING_KEY)
                .build();
    }

    // 审核重试队列
    @Bean
    public Queue moderationRetryQueue() {
        return QueueBuilder.durable(MODERATION_RETRY_QUEUE)
                .withArgument("x-dead-letter-exchange", MODERATION_EXCHANGE)
                .withArgument("x-dead-letter-routing-key", MODERATION_ROUTING_KEY)
                .withArgument("x-message-ttl", 60000) // 1 分钟后重试
                .build();
    }

    //审核死信队列
    @Bean
    public Queue moderationDlxQueue() {
        return QueueBuilder.durable(MODERATION_DLX_QUEUE).build();
    }

    // 审核交换机定义
    @Bean
    public DirectExchange moderationExchange() {
        return new DirectExchange(MODERATION_EXCHANGE);
    }
    // 审核重试交换机定义
    @Bean
    public DirectExchange moderationRetryExchange() {
        return new DirectExchange(MODERATION_RETRY_EXCHANGE);

    }
    // 审核死信交换机定义
    @Bean
    public DirectExchange moderationDlxExchange() {
        return new DirectExchange(MODERATION_DLX_EXCHANGE);
    }

    // 审核绑定关系定义
    @Bean
    public Binding moderationBinding() {
        return BindingBuilder.bind(moderationQueue())
                .to(moderationExchange())
                .with(MODERATION_ROUTING_KEY);
    }
    // 审核重试绑定关系定义
    @Bean
    public Binding moderationRetryBinding() {
        return BindingBuilder.bind(moderationRetryQueue())
                .to(moderationRetryExchange())
                .with(MODERATION_RETRY_ROUTING_KEY);
    }
    // 审核死信绑定关系定义
    @Bean
    public Binding moderationDlxBinding() {
        return BindingBuilder.bind(moderationDlxQueue())
                .to(moderationDlxExchange())
                .with(MODERATION_DLX_ROUTING_KEY);
    }
}
