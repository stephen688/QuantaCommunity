package com.quanta.demo0.notification.config;

import org.springframework.amqp.core.*;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 通知推送 RabbitMQ 拓扑。
 *
 * 职责：只声明该领域 RabbitMQ 队列、交换机和绑定；
 * 边界：消息发送与可靠投递由领域 Producer 和 platform/mq 负责。
 */
@Configuration
public class NotificationMQConfig {

    // 通知推送队列常量
    public static final String NOTIFICATION_QUEUE = "notification.queue";
    public static final String NOTIFICATION_EXCHANGE = "notification.exchange";
    public static final String NOTIFICATION_ROUTING_KEY = "notification.push";

    public static final String NOTIFICATION_RETRY_QUEUE = "notification.retry.queue";
    public static final String NOTIFICATION_RETRY_EXCHANGE = "notification.retry.exchange";
    public static final String NOTIFICATION_RETRY_ROUTING_KEY = "notification.retry";

    public static final String NOTIFICATION_DLX_QUEUE = "notification.dlx.queue";
    public static final String NOTIFICATION_DLX_EXCHANGE = "notification.dlx.exchange";
    public static final String NOTIFICATION_DLX_ROUTING_KEY = "notification.dlx";

   // 通知推送队列定义
    @Bean

    public Queue notificationQueue() {
        return QueueBuilder.durable(NOTIFICATION_QUEUE).build();
    }

    /**
     * 通知重试队列。
     * 消息停留 60 秒后重新进入通知主队列。
     */
    @Bean
    public Queue notificationRetryQueue() {
        return QueueBuilder.durable(NOTIFICATION_RETRY_QUEUE)
                .withArgument("x-message-ttl", 60000)
                .withArgument("x-dead-letter-exchange", NOTIFICATION_EXCHANGE)
                .withArgument("x-dead-letter-routing-key", NOTIFICATION_ROUTING_KEY)
                .build();
    }

    @Bean
    public Queue notificationDlxQueue() {
        return QueueBuilder.durable(NOTIFICATION_DLX_QUEUE).build();
    }

    // 通知推送交换机定义
    @Bean
    public DirectExchange notificationExchange() {
        return new DirectExchange(NOTIFICATION_EXCHANGE);
    }

    // 通知重试交换机定义
    @Bean
    public DirectExchange notificationRetryExchange() {
        return new DirectExchange(NOTIFICATION_RETRY_EXCHANGE);
    }

    @Bean
    public DirectExchange notificationDlxExchange() {
        return new DirectExchange(NOTIFICATION_DLX_EXCHANGE);
    }

    // 通知推送绑定关系定义
    @Bean
    public Binding notificationBinding() {
        return BindingBuilder.bind(notificationQueue())
                .to(notificationExchange())
                .with(NOTIFICATION_ROUTING_KEY);
    }

    // 通知重试绑定关系定义
    @Bean
    public Binding notificationRetryBinding() {
        return BindingBuilder.bind(notificationRetryQueue()).to(notificationRetryExchange()).with(NOTIFICATION_RETRY_ROUTING_KEY);
    }
    // 通知死信绑定关系定义
    @Bean
    public Binding notificationDlxBinding() {
        return BindingBuilder.bind(notificationDlxQueue()).to(notificationDlxExchange()).with(NOTIFICATION_DLX_ROUTING_KEY);
    }
}
