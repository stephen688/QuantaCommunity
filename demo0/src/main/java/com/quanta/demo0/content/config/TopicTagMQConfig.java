package com.quanta.demo0.content.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 内容主题标签 RabbitMQ 拓扑。
 *
 * 职责：声明主题标签主队列、延迟重试队列和死信队列；
 * 边界：首发事件由 OutboxDispatcher 负责，本配置不发送消息。
 *
 * ============================================================
 * 【拓扑总览：一条"帖子打标"消息的完整旅程】
 * ============================================================
 *   帖子审核通过 → ContentEventProducer 在发布事务里登记 Outbox 事件 →
 *   OutboxDispatcher 投到 TOPIC_TAG_EXCHANGE → TOPIC_TAG_QUEUE →
 *   ContentTopicTagConsumer（@RabbitListener + Inbox 幂等）调 LLM 打标并回写 tags。
 *
 *   失败链路：重试次数未超限时，Consumer 把 retryCount+1 的消息发进
 *   RETRY_EXCHANGE → retry 队列躺 60 秒（x-message-ttl）→ 死信回主交换机重投；
 *   超限或毒消息 → 主队列 DLX → dlx 队列留档等待人工/运营重放。
 *
 * ============================================================
 * 【与 ContentMQConfig 同构，但重试计数的位置不同】
 * ============================================================
 * QuantaBot 链路的重试语义在外部 Python 消费方手里；本链路的 retryCount
 * 放在 ContentTopicTagMessage 消息体里随消息流转，Inbox 表同步记账
 * （markRetry/markDead）。**broker 只负责"延迟再投一次"，业务重试状态必须
 * 自己记** —— 否则多实例/重启后没人知道这条消息已经试过几次。
 *
 * 【TTL 60000 与 ContentTopicProperties.retryDelaySeconds 的关系】
 * 队列级 x-message-ttl 是 broker 的真实投递延迟；properties 里的
 * retryDelaySeconds 只是 Inbox 中的重试期限记账，两者默认同为 60 秒。
 * 调整重试节奏时**两处必须一起改**，否则"约定的重试窗口"和实际节奏对不上。
 */
@Configuration
public class TopicTagMQConfig {

    // 【命名即契约】队列/交换机/路由键常量被 Consumer、Producer、Outbox 路由共用，
    // 改名等于换一条链路，必须整体评估。
    public static final String TOPIC_TAG_QUEUE = "content.topic.tag.queue";
    public static final String TOPIC_TAG_EXCHANGE = "content.topic.tag.exchange";
    public static final String TOPIC_TAG_ROUTING_KEY = "content.topic.tag";

    public static final String TOPIC_TAG_RETRY_QUEUE = "content.topic.tag.retry.queue";
    public static final String TOPIC_TAG_RETRY_EXCHANGE = "content.topic.tag.retry.exchange";
    public static final String TOPIC_TAG_RETRY_ROUTING_KEY = "content.topic.tag.retry";

    public static final String TOPIC_TAG_DLX_QUEUE = "content.topic.tag.dlx.queue";
    public static final String TOPIC_TAG_DLX_EXCHANGE = "content.topic.tag.dlx.exchange";
    public static final String TOPIC_TAG_DLX_ROUTING_KEY = "content.topic.tag.dlx";

    /**
     * 打标主队列：消费端 basicNack(requeue=false) 时，按 DLX 参数进入死信交换机。
     * durable：打标任务由 Outbox 事件驱动，队列丢了等于这批帖子永远不打标。
     */
    @Bean
    public Queue topicTagQueue() {
        return QueueBuilder.durable(TOPIC_TAG_QUEUE)
                .withArgument("x-dead-letter-exchange", TOPIC_TAG_DLX_EXCHANGE)
                .withArgument("x-dead-letter-routing-key", TOPIC_TAG_DLX_ROUTING_KEY).build();
    }

    /**
     * 延迟重试队列：x-message-ttl=60000ms 到期后死信回主交换机重投。
     * 【注意】队列级 TTL 对所有消息统一 60 秒；总重试次数由消息体里的
     * retryCount + Inbox 记账控制，队列本身不管次数。
     */
    @Bean
    public Queue topicTagRetryQueue() {
        return QueueBuilder.durable(TOPIC_TAG_RETRY_QUEUE)
                .withArgument("x-message-ttl", 60000)
                .withArgument("x-dead-letter-exchange", TOPIC_TAG_EXCHANGE)
                .withArgument("x-dead-letter-routing-key", TOPIC_TAG_ROUTING_KEY)
                .build();
    }

    /**
     * 最终死信队列：无消费者、无 TTL，消息留档等待人工/运营重放。
     * 【运维信号】深度 > 0 = 重试超限的打标任务在堆积。
     */
    @Bean
    public Queue topicTagDlxQueue() {
        return QueueBuilder.durable(TOPIC_TAG_DLX_QUEUE).build();
    }

    // 三个 Direct 交换机（路由键精确匹配，理由见 ContentMQConfig 的说明）。
    @Bean
    public DirectExchange topicTagExchange() {
        return new DirectExchange(TOPIC_TAG_EXCHANGE);
    }

    @Bean
    public DirectExchange topicTagRetryExchange() {
        return new DirectExchange(TOPIC_TAG_RETRY_EXCHANGE);
    }

    @Bean
    public DirectExchange topicTagDlxExchange() {
        return new DirectExchange(TOPIC_TAG_DLX_EXCHANGE);
    }

    // 三个绑定：启动时由 RabbitAdmin 声明，参数不一致会报错（防拓扑漂移）。
    @Bean
    public Binding topicTagBinding() {
        return BindingBuilder.bind(topicTagQueue())
                .to(topicTagExchange())
                .with(TOPIC_TAG_ROUTING_KEY);
    }

    @Bean
    public Binding topicTagRetryBinding() {
        return BindingBuilder.bind(topicTagRetryQueue())
                .to(topicTagRetryExchange())
                .with(TOPIC_TAG_RETRY_ROUTING_KEY);
    }

    @Bean
    public Binding topicTagDlxBinding() {
        return BindingBuilder.bind(topicTagDlxQueue())
                .to(topicTagDlxExchange())
                .with(TOPIC_TAG_DLX_ROUTING_KEY);
    }
}
