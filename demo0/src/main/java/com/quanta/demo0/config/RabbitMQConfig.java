package com.quanta.demo0.config;

import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * RabbitMQ 配置类
 * 作用：定义队列、交换机、绑定关系、消息转换器
 */
@Configuration
public class RabbitMQConfig {
//TODO:后续可以把这些常量放到一个专门的类里，或者用枚举来管理不同模块的队列和交换机，增加可维护性和可读性

    // ========== 常量定义（队列名、交换机名、路由键） ==========

    /**
     * Feed 流推送队列名称
     * 作用：存储待处理的 Feed 推送消息
     */
    public static final String FEED_PUSH_QUEUE = "feed.push.queue";

    /**
     * Feed 流推送交换机名称
     * 作用：接收生产者发送的消息，并根据路由键分发到队列
     */
    public static final String FEED_PUSH_EXCHANGE = "feed.push.exchange";

    /**
     * Feed 流推送路由键
     * 作用：匹配消息应该发送到哪个队列
     */
    public static final String FEED_PUSH_ROUTING_KEY = "feed.push";


    // 重试队列（延迟后回主队列）
    public static final String FEED_PUSH_RETRY_QUEUE = "feed.push.retry.queue";
    public static final String FEED_PUSH_RETRY_EXCHANGE = "feed.push.retry.exchange";
    public static final String FEED_PUSH_RETRY_ROUTING_KEY = "feed.push.retry";

    // 最终死信队列（超过重试次数后进入）
    public static final String FEED_PUSH_DLX_QUEUE = "feed.push.dlx.queue";
    public static final String FEED_PUSH_DLX_EXCHANGE = "feed.push.dlx.exchange";
    public static final String FEED_PUSH_DLX_ROUTING_KEY = "feed.push.dlx";



   // Feed 流删除队列常量
   public static final String FEED_DELETE_QUEUE = "feed.delete.queue";
    public static final String FEED_DELETE_EXCHANGE = "feed.delete.exchange";
    public static final String FEED_DELETE_ROUTING_KEY = "feed.delete";

    public static final String FEED_DELETE_RETRY_QUEUE = "feed.delete.retry.queue";
    public static final String FEED_DELETE_RETRY_EXCHANGE = "feed.delete.retry.exchange";
    public static final String FEED_DELETE_RETRY_ROUTING_KEY = "feed.delete.retry";

    public static final String FEED_DELETE_DLX_QUEUE = "feed.delete.dlx.queue";
    public static final String FEED_DELETE_DLX_EXCHANGE = "feed.delete.dlx.exchange";
    public static final String FEED_DELETE_DLX_ROUTING_KEY = "feed.delete.dlx";

    //热点内容更新队列常量
    public static final String HOT_SCORE_UPDATE_QUEUE = "hot.score.update.queue";
    public static final String HOT_SCORE_UPDATE_EXCHANGE = "hot.score.update.exchange";
    public static final String HOT_SCORE_UPDATE_ROUTING_KEY = "hot.score.update";

    public static final String HOT_SCORE_RETRY_QUEUE = "hot.score.retry.queue";
    public static final String HOT_SCORE_RETRY_EXCHANGE = "hot.score.retry.exchange";
    public static final String HOT_SCORE_RETRY_ROUTING_KEY = "hot.score.retry";

    public static final String HOT_SCORE_DLX_QUEUE = "hot.score.dlx.queue";
    public static final String HOT_SCORE_DLX_EXCHANGE = "hot.score.dlx.exchange";
    public static final String HOT_SCORE_DLX_ROUTING_KEY = "hot.score.dlx";



    // 通知推送队列常量
    public static final String NOTIFICATION_QUEUE = "notification.queue";
    public static final String NOTIFICATION_EXCHANGE = "notification.exchange";
    public static final String NOTIFICATION_ROUTING_KEY = "notification.push";

    public static final String MODERATION_QUEUE = "moderation.queue";
    public static final String MODERATION_EXCHANGE = "moderation.exchange";
    public static final String MODERATION_ROUTING_KEY = "moderation.task";

    public static final String MODERATION_RETRY_QUEUE = "moderation.retry.queue";
    public static final String MODERATION_RETRY_EXCHANGE = "moderation.retry.exchange";
    public static final String MODERATION_RETRY_ROUTING_KEY = "moderation.retry";

    public static final String MODERATION_DLX_QUEUE = "moderation.dlx.queue";
    public static final String MODERATION_DLX_EXCHANGE = "moderation.dlx.exchange";
    public static final String MODERATION_DLX_ROUTING_KEY = "moderation.dlx";

    public static final String NOTIFICATION_RETRY_QUEUE = "notification.retry.queue";
    public static final String NOTIFICATION_RETRY_EXCHANGE = "notification.retry.exchange";
    public static final String NOTIFICATION_RETRY_ROUTING_KEY = "notification.retry";

    public static final String NOTIFICATION_DLX_QUEUE = "notification.dlx.queue";
    public static final String NOTIFICATION_DLX_EXCHANGE = "notification.dlx.exchange";
    public static final String NOTIFICATION_DLX_ROUTING_KEY = "notification.dlx";

    public static final String SEARCH_RECONCILE_QUEUE = "search.reconcile.queue";
    public static final String SEARCH_RECONCILE_EXCHANGE = "search.reconcile.exchange";
    public static final String SEARCH_RECONCILE_ROUTING_KEY = "search.reconcile";

    public static final String SEARCH_RECONCILE_RETRY_QUEUE = "search.reconcile.retry.queue";
    public static final String SEARCH_RECONCILE_RETRY_EXCHANGE = "search.reconcile.retry.exchange";
    public static final String SEARCH_RECONCILE_RETRY_ROUTING_KEY = "search.reconcile.retry";

    public static final String SEARCH_RECONCILE_DLX_QUEUE = "search.reconcile.dlx.queue";
    public static final String SEARCH_RECONCILE_DLX_EXCHANGE = "search.reconcile.dlx.exchange";
    public static final String SEARCH_RECONCILE_DLX_ROUTING_KEY = "search.reconcile.dlx";

    // ========== Bean 1：消息转换器 ==========

    /**
     * 消息转换器（JSON 格式）
     * 作用：将 Java 对象转换为 JSON 格式的消息，方便传输和阅读
     */
    @Bean
    public MessageConverter messageConverter() {
        return new Jackson2JsonMessageConverter();
    }

    // ========== Bean 2：RabbitTemplate（消息发送模板） ==========

    /**
     * RabbitTemplate 配置
     * 作用：生产者用来发送消息的工具类
     */
    @Bean
    public RabbitTemplate rabbitTemplate(ConnectionFactory connectionFactory) {
        RabbitTemplate rabbitTemplate = new RabbitTemplate(connectionFactory);

        // 设置消息转换器（使用 JSON 格式）
        rabbitTemplate.setMessageConverter(messageConverter());

        // 开启发布者确认（确保消息到达 RabbitMQ 服务器）
        rabbitTemplate.setConfirmCallback((correlationData, ack, cause) -> {
            if (!ack) {
                // 消息发送失败，记录日志
                System.err.println("消息发送失败：" + cause);
            }
        });


         //交换机无法路由到队列时，必须return

        rabbitTemplate.setMandatory(true);

        // 开启发布者返回（确保消息成功路由到队列）
        rabbitTemplate.setReturnsCallback(returned -> {
            System.err.println("消息路由失败：" + returned);
        });

        return rabbitTemplate;
    }

    // ========== Bean 3：队列定义 ==========

    /**
     * Feed 流推送队列
     * 作用：存储待处理的 Feed 推送消息
     */
    @Bean
    public Queue feedPushQueue() {
        return QueueBuilder.durable(FEED_PUSH_QUEUE)  // 持久化队列（RabbitMQ 重启后队列不丢失）
              //  .withArgument("x-message-ttl", 60000) // 消息过期时间 60 秒（可选）
                .withArgument("x-dead-letter-exchange", FEED_PUSH_DLX_EXCHANGE)// 死信交换机（消息过期或被拒绝后进入）
                .withArgument("x-dead-letter-routing-key", FEED_PUSH_DLX_ROUTING_KEY)// 死信路由键
                .build();
    }


    // 重试队列（延迟后回主队列）
    @Bean
    public Queue feedPushRetryQueue() {
        return QueueBuilder.durable(FEED_PUSH_RETRY_QUEUE)
                .withArgument("x-message-ttl", 5000) // 保留现有队列参数，避免 RabbitMQ 重声明冲突
                .withArgument("x-dead-letter-exchange", FEED_PUSH_EXCHANGE)// 重试队列的死信交换机是主交换机，过期后回主队列
                .withArgument("x-dead-letter-routing-key", FEED_PUSH_ROUTING_KEY)// 重试队列的死信路由键是主队列的路由键
                .build();
    }

// 死信队列（超过重试次数后进入）
    @Bean
    public Queue feedPushDlxQueue() {
        return QueueBuilder.durable(FEED_PUSH_DLX_QUEUE).
                build();
    }

    // Feed 流删除队列定义
    @Bean
    public Queue feedDeleteQueue() {
        return QueueBuilder.durable(FEED_DELETE_QUEUE).build();
    }

    @Bean
    public Queue feedDeleteRetryQueue() {
        return QueueBuilder.durable(FEED_DELETE_RETRY_QUEUE)
                .withArgument("x-message-ttl", 60000)
                .withArgument("x-dead-letter-exchange", FEED_DELETE_EXCHANGE)
                .withArgument("x-dead-letter-routing-key", FEED_DELETE_ROUTING_KEY)
                .build();
    }

    @Bean
    public Queue feedDeleteDlxQueue() {
        return QueueBuilder.durable(FEED_DELETE_DLX_QUEUE).build();
    }

    // 热点内容更新队列定义
    @Bean
    public Queue hotScoreUpdateQueue() {
        return QueueBuilder.durable(HOT_SCORE_UPDATE_QUEUE).build();
    }

    @Bean
    public Queue hotScoreRetryQueue() {
        return QueueBuilder.durable(HOT_SCORE_RETRY_QUEUE)
                .withArgument("x-message-ttl", 60000)
                .withArgument("x-dead-letter-exchange", HOT_SCORE_UPDATE_EXCHANGE)
                .withArgument("x-dead-letter-routing-key", HOT_SCORE_UPDATE_ROUTING_KEY)
                .build();
    }

    @Bean
    public Queue hotScoreDlxQueue() {
        return QueueBuilder.durable(HOT_SCORE_DLX_QUEUE).build();
    }


   // 通知推送队列定义
    @Bean

    public Queue notificationQueue() {
        return QueueBuilder.durable(NOTIFICATION_QUEUE).build();
    }

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

    // ========== Bean 4：交换机定义 ==========



    /**
     * Feed 流推送交换机（Direct 类型）
     * 作用：接收消息并根据路由键精确匹配到队列
     */
    @Bean
    public DirectExchange feedPushExchange() {
        return new DirectExchange(FEED_PUSH_EXCHANGE);
    }

    // 重试交换机
    @Bean
    public DirectExchange feedPushRetryExchange() {
        return new DirectExchange(FEED_PUSH_RETRY_EXCHANGE);
    }

    // 死信交换机
    @Bean
    public DirectExchange feedPushDlxExchange() {
        return new DirectExchange(FEED_PUSH_DLX_EXCHANGE);
    }

    // Feed 流删除交换机定义
    @Bean
    public DirectExchange feedDeleteExchange() {
        return new DirectExchange(FEED_DELETE_EXCHANGE);
    }

    @Bean
    public DirectExchange feedDeleteRetryExchange() {
        return new DirectExchange(FEED_DELETE_RETRY_EXCHANGE);
    }

    @Bean
    public DirectExchange feedDeleteDlxExchange() {
        return new DirectExchange(FEED_DELETE_DLX_EXCHANGE);
    }
    // 热点内容更新交换机定义
    @Bean
    public DirectExchange hotScoreUpdateExchange() {
        return new DirectExchange(HOT_SCORE_UPDATE_EXCHANGE);
    }

    @Bean
    public DirectExchange hotScoreRetryExchange() {
        return new DirectExchange(HOT_SCORE_RETRY_EXCHANGE);
    }

    @Bean
    public DirectExchange hotScoreDlxExchange() {
        return new DirectExchange(HOT_SCORE_DLX_EXCHANGE);
    }

    // 通知推送交换机定义
    @Bean
    public DirectExchange notificationExchange() {
        return new DirectExchange(NOTIFICATION_EXCHANGE);
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
    // 通知重试交换机定义
    @Bean
    public DirectExchange notificationRetryExchange() {
        return new DirectExchange(NOTIFICATION_RETRY_EXCHANGE);
    }

    @Bean
    public DirectExchange notificationDlxExchange() {
        return new DirectExchange(NOTIFICATION_DLX_EXCHANGE);
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



    // ========== Bean 5：绑定关系定义 ==========

    /**
     * 绑定队列到交换机
     * 作用：将队列和交换机通过路由键关联起来
     * 流程：生产者 → 交换机 → 路由键匹配 → 队列 → 消费者
     */
    @Bean
    public Binding feedPushBinding() {
        return BindingBuilder.bind(feedPushQueue())      // 绑定哪个队列
                .to(feedPushExchange())                   // 绑定到哪个交换机
                .with(FEED_PUSH_ROUTING_KEY);             // 使用哪个路由键
    }
    // 重试队列绑定
    @Bean
    public Binding feedPushRetryBinding() {
        return BindingBuilder.bind(feedPushRetryQueue())
                .to(feedPushRetryExchange())
                .with(FEED_PUSH_RETRY_ROUTING_KEY);
    }
    // 死信队列绑定
    @Bean
    public Binding feedPushDlxBinding() {
        return BindingBuilder.bind(feedPushDlxQueue())
                .to(feedPushDlxExchange())
                .with(FEED_PUSH_DLX_ROUTING_KEY);
    }

    // Feed 流删除绑定关系定义
    @Bean
    public Binding feedDeleteBinding() {
        return BindingBuilder.bind(feedDeleteQueue())
                .to(feedDeleteExchange())
                .with(FEED_DELETE_ROUTING_KEY);
    }

    @Bean
    public Binding feedDeleteRetryBinding() {
        return BindingBuilder.bind(feedDeleteRetryQueue()).to(feedDeleteRetryExchange()).with(FEED_DELETE_RETRY_ROUTING_KEY);
    }

    @Bean
    public Binding feedDeleteDlxBinding() {
        return BindingBuilder.bind(feedDeleteDlxQueue()).to(feedDeleteDlxExchange()).with(FEED_DELETE_DLX_ROUTING_KEY);
    }

    // 热点内容更新绑定关系定义
    @Bean
    public Binding hotScoreUpdateBinding() {
        return BindingBuilder.bind(hotScoreUpdateQueue())
                .to(hotScoreUpdateExchange())
                .with(HOT_SCORE_UPDATE_ROUTING_KEY);
    }

    @Bean
    public Binding hotScoreRetryBinding() {
        return BindingBuilder.bind(hotScoreRetryQueue()).to(hotScoreRetryExchange()).with(HOT_SCORE_RETRY_ROUTING_KEY);
    }

    @Bean
    public Binding hotScoreDlxBinding() {
        return BindingBuilder.bind(hotScoreDlxQueue()).to(hotScoreDlxExchange()).with(HOT_SCORE_DLX_ROUTING_KEY);
    }
    // 通知推送绑定关系定义
    @Bean
    public Binding notificationBinding() {
        return BindingBuilder.bind(notificationQueue())
                .to(notificationExchange())
                .with(NOTIFICATION_ROUTING_KEY);
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
