package com.quanta.demo0.content.config;

import org.springframework.amqp.core.*;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 内容与 QuantaBot 触发链路 RabbitMQ 拓扑。
 *
 * 职责：只声明该领域 RabbitMQ 队列、交换机和绑定；
 * 边界：消息发送与可靠投递由领域 Producer 和 platform/mq 负责。
 */
@Configuration
public class ContentMQConfig {

    // ========== QuantaBot 触发链路（C-1 契约）==========
    // 名称与 Phase 0 契约及 QuantaBot consumer.py 的 QUANTABOT_QUEUE 逐字一致。
    public static final String BOT_MENTION_EXCHANGE = "quantabot.exchange";
    public static final String BOT_MENTION_QUEUE = "quantabot.comment.queue";
    public static final String BOT_MENTION_ROUTING_KEY = "quantabot.comment.created";

    public static final String BOT_MENTION_RETRY_QUEUE = "quantabot.comment.retry.queue";
    public static final String BOT_MENTION_RETRY_EXCHANGE = "quantabot.comment.retry.exchange";
    public static final String BOT_MENTION_RETRY_ROUTING_KEY = "quantabot.comment.retry";

    public static final String BOT_MENTION_DLX_QUEUE = "quantabot.comment.dlx.queue";
    public static final String BOT_MENTION_DLX_EXCHANGE = "quantabot.comment.dlx.exchange";
    public static final String BOT_MENTION_DLX_ROUTING_KEY = "quantabot.comment.dlx";

    // ========== QuantaBot 触发链路队列（C-1）==========

    /**
     * bot 触发主队列；消费失败时进入最终死信交换机。
     */
    @Bean
    public Queue botMentionQueue() {
        return QueueBuilder.durable(BOT_MENTION_QUEUE)
                .withArgument("x-dead-letter-exchange", BOT_MENTION_DLX_EXCHANGE)
                .withArgument("x-dead-letter-routing-key", BOT_MENTION_DLX_ROUTING_KEY)
                .build();
    }

    /**
     * bot 触发重试队列；消息 TTL 到期后回到主交换机。
     */
    @Bean
    public Queue botMentionRetryQueue() {
        return QueueBuilder.durable(BOT_MENTION_RETRY_QUEUE)
                .withArgument("x-message-ttl", 60000)
                .withArgument("x-dead-letter-exchange", BOT_MENTION_EXCHANGE)
                .withArgument("x-dead-letter-routing-key", BOT_MENTION_ROUTING_KEY)
                .build();
    }

    /**
     * bot 触发最终死信队列。
     */
    @Bean
    public Queue botMentionDlxQueue() {
        return QueueBuilder.durable(BOT_MENTION_DLX_QUEUE).build();
    }

    // ========== QuantaBot 触发链路交换机（C-1）==========

    @Bean
    public DirectExchange botMentionExchange() {
        return new DirectExchange(BOT_MENTION_EXCHANGE);
    }

    @Bean
    public DirectExchange botMentionRetryExchange() {
        return new DirectExchange(BOT_MENTION_RETRY_EXCHANGE);
    }

    @Bean
    public DirectExchange botMentionDlxExchange() {
        return new DirectExchange(BOT_MENTION_DLX_EXCHANGE);
    }

    // ========== QuantaBot 触发链路绑定（C-1）==========

    @Bean
    public Binding botMentionBinding() {
        return BindingBuilder.bind(botMentionQueue())
                .to(botMentionExchange())
                .with(BOT_MENTION_ROUTING_KEY);
    }

    @Bean
    public Binding botMentionRetryBinding() {
        return BindingBuilder.bind(botMentionRetryQueue())
                .to(botMentionRetryExchange())
                .with(BOT_MENTION_RETRY_ROUTING_KEY);
    }

    @Bean
    public Binding botMentionDlxBinding() {
        return BindingBuilder.bind(botMentionDlxQueue())
                .to(botMentionDlxExchange())
                .with(BOT_MENTION_DLX_ROUTING_KEY);
    }
}
