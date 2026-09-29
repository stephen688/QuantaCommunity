package com.quanta.demo0.search.config;

import org.springframework.amqp.core.*;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 搜索索引对账 RabbitMQ 拓扑。
 *
 * 职责：只声明该领域 RabbitMQ 队列、交换机和绑定；
 * 边界：消息发送与可靠投递由领域 Producer 和 platform/mq 负责。
 */
@Configuration
public class SearchMQConfig {

    public static final String SEARCH_RECONCILE_QUEUE = "search.reconcile.queue";
    public static final String SEARCH_RECONCILE_EXCHANGE = "search.reconcile.exchange";
    public static final String SEARCH_RECONCILE_ROUTING_KEY = "search.reconcile";

    public static final String SEARCH_RECONCILE_RETRY_QUEUE = "search.reconcile.retry.queue";
    public static final String SEARCH_RECONCILE_RETRY_EXCHANGE = "search.reconcile.retry.exchange";
    public static final String SEARCH_RECONCILE_RETRY_ROUTING_KEY = "search.reconcile.retry";

    public static final String SEARCH_RECONCILE_DLX_QUEUE = "search.reconcile.dlx.queue";
    public static final String SEARCH_RECONCILE_DLX_EXCHANGE = "search.reconcile.dlx.exchange";
    public static final String SEARCH_RECONCILE_DLX_ROUTING_KEY = "search.reconcile.dlx";

    /**
     * ES 校准主队列。
     */
    @Bean
    public Queue searchReconcileQueue() {
        return QueueBuilder.durable(SEARCH_RECONCILE_QUEUE).build();
    }

    /**
     * ES 校准重试队列。
     * 消息停留 60 秒后，通过死信配置重新回到主队列。
     */
    @Bean
    public Queue searchReconcileRetryQueue() {
        return QueueBuilder.durable(SEARCH_RECONCILE_RETRY_QUEUE)
                .withArgument("x-message-ttl", 60000)
                .withArgument("x-dead-letter-exchange", SEARCH_RECONCILE_EXCHANGE)
                .withArgument("x-dead-letter-routing-key", SEARCH_RECONCILE_ROUTING_KEY)
                .build();
    }

    /**
     * ES 校准最终死信队列。
     */
    @Bean
    public Queue searchReconcileDlxQueue() {
        return QueueBuilder.durable(SEARCH_RECONCILE_DLX_QUEUE).build();
    }

    @Bean
    public DirectExchange searchReconcileExchange() {
        return new DirectExchange(SEARCH_RECONCILE_EXCHANGE);
    }

    @Bean
    public DirectExchange searchReconcileRetryExchange() {
        return new DirectExchange(SEARCH_RECONCILE_RETRY_EXCHANGE);
    }

    @Bean
    public DirectExchange searchReconcileDlxExchange() {
        return new DirectExchange(SEARCH_RECONCILE_DLX_EXCHANGE);
    }

    @Bean
    public Binding searchReconcileBinding() {
        return BindingBuilder.bind(searchReconcileQueue())
                .to(searchReconcileExchange())
                .with(SEARCH_RECONCILE_ROUTING_KEY);
    }

    @Bean
    public Binding searchReconcileRetryBinding() {
        return BindingBuilder.bind(searchReconcileRetryQueue())
                .to(searchReconcileRetryExchange())
                .with(SEARCH_RECONCILE_RETRY_ROUTING_KEY);
    }

    @Bean
    public Binding searchReconcileDlxBinding() {
        return BindingBuilder.bind(searchReconcileDlxQueue())
                .to(searchReconcileDlxExchange())
                .with(SEARCH_RECONCILE_DLX_ROUTING_KEY);
    }
}
