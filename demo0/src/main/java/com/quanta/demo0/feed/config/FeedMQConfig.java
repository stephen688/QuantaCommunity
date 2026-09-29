package com.quanta.demo0.feed.config;

import org.springframework.amqp.core.*;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Feed、热度和用户行为画像 RabbitMQ 拓扑。
 *
 * 职责：只声明该领域 RabbitMQ 队列、交换机和绑定；
 * 边界：消息发送与可靠投递由领域 Producer 和 platform/mq 负责。
 */
@Configuration
public class FeedMQConfig {

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

    //用户行为画像更新队列常量（推荐流个性化 D2）
    public static final String USER_BEHAVIOR_QUEUE = "user.behavior.queue";
    public static final String USER_BEHAVIOR_EXCHANGE = "user.behavior.exchange";
    public static final String USER_BEHAVIOR_ROUTING_KEY = "user.behavior";

    public static final String USER_BEHAVIOR_RETRY_QUEUE = "user.behavior.retry.queue";
    public static final String USER_BEHAVIOR_RETRY_EXCHANGE = "user.behavior.retry.exchange";
    public static final String USER_BEHAVIOR_RETRY_ROUTING_KEY = "user.behavior.retry";

    public static final String USER_BEHAVIOR_DLX_QUEUE = "user.behavior.dlx.queue";
    public static final String USER_BEHAVIOR_DLX_EXCHANGE = "user.behavior.dlx.exchange";
    public static final String USER_BEHAVIOR_DLX_ROUTING_KEY = "user.behavior.dlx";

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

    // 用户行为画像更新队列定义（照抄 hot score 拓扑：主队列无 DLX 参数，消费失败由消费者显式转发重试/死信）
    @Bean
    public Queue userBehaviorQueue() {
        return QueueBuilder.durable(USER_BEHAVIOR_QUEUE).build();
    }

    @Bean
    public Queue userBehaviorRetryQueue() {
        return QueueBuilder.durable(USER_BEHAVIOR_RETRY_QUEUE)
                .withArgument("x-message-ttl", 60000)
                .withArgument("x-dead-letter-exchange", USER_BEHAVIOR_EXCHANGE)
                .withArgument("x-dead-letter-routing-key", USER_BEHAVIOR_ROUTING_KEY)
                .build();
    }

    @Bean
    public Queue userBehaviorDlxQueue() {
        return QueueBuilder.durable(USER_BEHAVIOR_DLX_QUEUE).build();
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

    // 用户行为画像更新交换机定义
    @Bean
    public DirectExchange userBehaviorExchange() {
        return new DirectExchange(USER_BEHAVIOR_EXCHANGE);
    }

    @Bean
    public DirectExchange userBehaviorRetryExchange() {
        return new DirectExchange(USER_BEHAVIOR_RETRY_EXCHANGE);
    }

    @Bean
    public DirectExchange userBehaviorDlxExchange() {
        return new DirectExchange(USER_BEHAVIOR_DLX_EXCHANGE);
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

    // 用户行为画像更新绑定关系定义
    @Bean
    public Binding userBehaviorBinding() {
        return BindingBuilder.bind(userBehaviorQueue())
                .to(userBehaviorExchange())
                .with(USER_BEHAVIOR_ROUTING_KEY);
    }

    @Bean
    public Binding userBehaviorRetryBinding() {
        return BindingBuilder.bind(userBehaviorRetryQueue()).to(userBehaviorRetryExchange()).with(USER_BEHAVIOR_RETRY_ROUTING_KEY);
    }

    @Bean
    public Binding userBehaviorDlxBinding() {
        return BindingBuilder.bind(userBehaviorDlxQueue()).to(userBehaviorDlxExchange()).with(USER_BEHAVIOR_DLX_ROUTING_KEY);
    }
}
