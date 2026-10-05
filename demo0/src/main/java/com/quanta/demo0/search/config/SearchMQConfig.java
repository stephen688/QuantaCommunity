package com.quanta.demo0.search.config;

import org.springframework.amqp.core.*;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 搜索索引对账 RabbitMQ 拓扑。
 *
 * 职责：只声明该领域 RabbitMQ 队列、交换机和绑定；
 * 边界：消息发送与可靠投递由领域 Producer 和 platform/mq 负责。
 *
 * ============================================================
 * 【拓扑全景：主队列 → 重试队列 → 主队列 → 死信队列】
 * ============================================================
 * 首投（OutboxDispatcher）→ search.reconcile.exchange
 *   --routing: search.reconcile--> search.reconcile.queue（@RabbitListener 消费）
 *
 * 消费失败/BUSY → search.reconcile.retry.exchange
 *   --routing: search.reconcile.retry--> search.reconcile.retry.queue
 *   （消息在此停留 60s，TTL 到期后被死信投回主交换机，等于延迟重试）
 *
 * 重试 3 次仍失败 / 畸形消息 → search.reconcile.dlx.exchange
 *   --routing: search.reconcile.dlx--> search.reconcile.dlx.queue（人工排查）
 *
 * ============================================================
 * 【为什么重试用"队列 TTL + 死信"实现，而不是 nack 重投？】
 * ============================================================
 * nack(requeue=true) 的消息会被**立即**重新消费，若失败是业务性的
 * （如 ES 长时间不可用），会形成高速失败循环。把消息先送进带
 * x-message-ttl=60000 的重试队列，到期后经 x-dead-letter-exchange
 * 自动转回主交换机，就得到了 60 秒的延迟重试——**不依赖
 * rabbitmq-delayed-message-exchange 插件，纯原生能力**。
 * 注意主队列本身没有挂死信参数：它的 nack 重回由代码显式控制，
 * 只有重试队列才配置死信转发。
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
    /**
     * ES 校准主队列。
     * durable 保证 broker 重启消息不丢；故意不挂死信参数——
     * 主队列的 nack 重回由消费者代码显式控制（requeue=true）。
     */
    @Bean
    public Queue searchReconcileQueue() {
        return QueueBuilder.durable(SEARCH_RECONCILE_QUEUE).build();
    }

    /**
     * ES 校准重试队列。
     * 消息停留 60 秒后，通过死信配置重新回到主队列。
     * 【三个参数缺一不可】x-message-ttl 定延迟时长；x-dead-letter-exchange /
     * x-dead-letter-routing-key 指明到期后转回主交换机 + 主队列的路由键，
     * 消费者代码里的 RETRY_DELAY_SECONDS=60 必须与此处保持一致。
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
     * 链路终点：只进不出，供人工排查后手工补偿；无消费者、无 TTL。
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
