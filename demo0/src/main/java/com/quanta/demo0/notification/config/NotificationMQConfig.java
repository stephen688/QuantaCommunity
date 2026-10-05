package com.quanta.demo0.notification.config;

import org.springframework.amqp.core.*;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 通知推送 RabbitMQ 拓扑。
 *
 * 职责：只声明该领域 RabbitMQ 队列、交换机和绑定；
 * 边界：消息发送与可靠投递由领域 Producer 和 platform/mq 负责。
 *
 * ============================================================
 * 【主队列 / 重试队列 / 死信队列各管什么？】
 * ============================================================
 * notification.queue 是**唯一有消费者**的队列（NotificationConsumer 监听）；
 * notification.retry.queue 没有任何消费者，靠 x-message-ttl=60000 让消息躺满
 * 60 秒，再由 broker 按 x-dead-letter-exchange 把消息死信回主交换机、
 * 重新路由进主队列——用"躺够时间自动死信"实现延迟重投；
 * notification.dlx.queue 同样没有消费者，是重试 3 次仍失败的消息的停放处，
 * 只进不出，保留现场供人工排查。
 *
 * 【与 moderation 域的一个差别】
 * moderation 的主队列自己挂了 DLX 参数（nack 不重入队时由 broker 死信）；
 * 本域主队列**没有** DLX 参数：消费失败的重试完全走"应用层转投 retry 队列"
 * （NotificationProducer.sendRetryTask），DLX 队列只由 sendDeadTask 显式投递，
 * broker 侧不会自作主张把消息搬进死信。
 */
@Configuration
public class NotificationMQConfig {

    // 通知推送队列常量
    // （主链路三件套：主队列 + retry + dlx，命名按 队列/交换机/路由键 成组）
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
    // 主队列只声明 durable，不挂任何 DLX/TTL 参数：延迟重试与判死均由应用层显式转投
    @Bean

    public Queue notificationQueue() {
        return QueueBuilder.durable(NOTIFICATION_QUEUE).build();
    }

    /**
     * 通知重试队列。
     * 消息停留 60 秒后重新进入通知主队列。
     *
     * 【没有消费者的延迟队列】实现"延迟重投"的惯用法：
     * x-message-ttl=60000 让消息躺 60 秒，x-dead-letter-exchange/routing-key
     * 指回主交换机 + 主路由键，躺满后 broker 自动把消息死信回主队列。
     * 注意 TTL 是队列级参数，改这里要同步核对消费端 RETRY_DELAY_SECONDS。
     */
    @Bean
    public Queue notificationRetryQueue() {
        return QueueBuilder.durable(NOTIFICATION_RETRY_QUEUE)
                .withArgument("x-message-ttl", 60000)
                .withArgument("x-dead-letter-exchange", NOTIFICATION_EXCHANGE)
                .withArgument("x-dead-letter-routing-key", NOTIFICATION_ROUTING_KEY)
                .build();
    }

    // 死信停放队列：没有消费者、没有 TTL，消息只进不出，保留最终失败现场
    @Bean
    public Queue notificationDlxQueue() {
        return QueueBuilder.durable(NOTIFICATION_DLX_QUEUE).build();
    }

    // 通知推送交换机定义
    // （Direct 类型：路由键精确匹配，本域只有一个固定路由键 notification.push）
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
