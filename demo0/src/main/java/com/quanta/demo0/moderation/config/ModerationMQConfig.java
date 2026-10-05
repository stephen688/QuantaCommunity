package com.quanta.demo0.moderation.config;

import org.springframework.amqp.core.*;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 内容审核 RabbitMQ 拓扑。
 *
 * 职责：只声明该领域 RabbitMQ 队列、交换机和绑定；
 * 边界：消息发送与可靠投递由领域 Producer 和 platform/mq 负责。
 *
 * ============================================================
 * 【三件套拓扑：主队列 + TTL 重试队列 + 死信队列】
 * ============================================================
 * 消息流（三个 DirectExchange，各配一个队列、一个精确 routing key）：
 *   首发消费：Outbox 中继 → moderation.exchange --moderation.task--> moderation.queue
 *   延迟重试：ModerationProducer → moderation.retry.exchange --moderation.retry-->
 *             moderation.retry.queue（x-message-ttl=60000 躺 1 分钟）到期死信
 *             回 moderation.exchange --moderation.task--> 主队列重新消费
 *   终点停放：消费端 nack(requeue=false) → moderation.dlx.exchange --moderation.dlx-->
 *             moderation.dlx.queue（人工排查/兜底）
 * 与 content 包 TopicTagMQConfig 的"主队列 DLX + TTL 重试队列"是同一套结构。
 *
 * 【为什么重试要走 TTL 队列，而不是 nack(requeue=true)？】
 * requeue 是**立即**重新入队，失败原因（比如阿里云接口抖动）短时间不会恢复，
 * 消息会被打回来反复空转、刷爆日志；TTL 队列让消息先"躺 60 秒"再回来，
 * **用 broker 的死信机制实现了天然的延迟重试**。注意该 TTL 与工作流里的
 * 重试间隔（ModerationWorkflowServiceImpl#RETRY_DELAY_SECONDS = 60s）保持一致，
 * 改一处要同步改另一处。
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
    // 消费端 basicNack(requeue=false) 时，消息会按下面两个死信参数自动转投死信交换机
    @Bean
    public Queue moderationQueue() {
        return QueueBuilder.durable(MODERATION_QUEUE)
                .withArgument("x-dead-letter-exchange", MODERATION_DLX_EXCHANGE)
                .withArgument("x-dead-letter-routing-key", MODERATION_DLX_ROUTING_KEY)
                .build();
    }

    // 审核重试队列
    // 没有消费者；消息躺满 x-message-ttl（60 秒）后由 broker 死信回主交换机，实现延迟重投
    @Bean
    public Queue moderationRetryQueue() {
        return QueueBuilder.durable(MODERATION_RETRY_QUEUE)
                .withArgument("x-dead-letter-exchange", MODERATION_EXCHANGE)
                .withArgument("x-dead-letter-routing-key", MODERATION_ROUTING_KEY)
                .withArgument("x-message-ttl", 60000) // 1 分钟后重试
                .build();
    }

    //审核死信队列
    // 不带任何死信参数——它是整条链路的终点，进来的消息停在队列里等人工处理
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
