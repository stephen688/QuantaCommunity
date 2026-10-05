package com.quanta.demo0.content.config;

import org.springframework.amqp.core.*;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 内容与 QuantaBot 触发链路 RabbitMQ 拓扑。
 *
 * 职责：只声明该领域 RabbitMQ 队列、交换机和绑定；
 * 边界：消息发送与可靠投递由领域 Producer 和 platform/mq 负责。
 *
 * ============================================================
 * 【拓扑总览：一条"评论 @ 机器人"消息的完整旅程】
 * ============================================================
 *   评论创建（comment 包）→ Outbox 事件 → OutboxDispatcher 按事件类型路由到
 *   BOT_MENTION_EXCHANGE（见 OutboxRouteRegistry）→ quantabot.comment.queue →
 *   **外部 QuantaBot 服务（Python consumer.py，QUANTABOT_QUEUE）消费** ——
 *   本项目没有它的 Java 消费者，队列名就是跨语言契约。
 *
 *   消费失败的两条出路：
 *   a) 重试：消息投到 BOT_MENTION_RETRY_EXCHANGE → retry 队列躺 60 秒
 *      （x-message-ttl）→ TTL 到期由 broker 死信回主交换机 → 重新消费；
 *   b) 放弃：主队列消费端 basicNack(requeue=false) → 队列声明的
 *      x-dead-letter-exchange 参数把它送进 DLX 交换机 → dlx 队列留档等人工处理。
 *
 * ============================================================
 * 【为什么用"TTL + 死信回主交换机"做延迟重试，而不是 requeue 或本地重试？】
 * ============================================================
 * 三种写法对比：
 *   - basicRequeue 立即重投：失败原因（下游抖动/超时）不会 1ms 内消失，
 *     消息在队列头空转，还可能堵死后面正常消息（队头阻塞）；
 *   - 消费者线程 sleep 重试：占着连接和 prefetch 名额干等，吞吐塌方；
 *   - **重试队列 + x-message-ttl**：失败消息先"存起来等 60 秒"，消费线程立刻
 *     释放去干后面的活；TTL 到期 broker 自动死信回主队列 —— 把"延迟"交给
 *     broker，消费线程只干立刻能干完的事。
 *
 * 【为什么三个队列/交换机全部 durable？】
 * 这是跨服务触发契约，消息丢了就是用户的 @ 没人理，业务侧无法补偿；
 * 全部 autoDelete 虽然省心，但 broker 重启或消费者短暂断开就可能连队列
 * 带积压消息一起蒸发。durable 队列 + 持久化消息 + publisher-confirm
 * （application.yml: correlated）+ 手动 ack（acknowledge-mode: manual）
 * 四件套合起来才是完整的 **at-least-once**。
 */
@Configuration
public class ContentMQConfig {

    // ========== QuantaBot 触发链路（C-1 契约）==========
    // 名称与 Phase 0 契约及 QuantaBot consumer.py 的 QUANTABOT_QUEUE 逐字一致。
    // 【命名即契约】以下常量是 Java 侧与 QuantaBot（Python）侧共享的字符串，
    // 改任何一个都必须两边同步发布；队列/交换机名在 broker 里是全局资源，禁止随手改。
    public static final String BOT_MENTION_EXCHANGE = "quantabot.exchange";//bot 触发主交换机
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
     *
     * DLX 参数在**队列声明时**固化：消费端 basicNack(requeue=false) 的那一刻，
     * broker 才按这两个参数执行死信转发。
     * 【注意】主队列没设 TTL —— 触发任务宁可等消费者慢慢处理，不能悄悄过期消失。
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
     *
     * x-message-ttl=60000（毫秒）+ 死信回主交换机 = 60 秒延迟重试。
     * 【注意】TTL 是队列级固定值，所有重试消息一视同仁；要做分级退避需另建多档队列。
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
     *
     * 没有消费者、没有 TTL —— 消息躺在这里等运营排查/重放。
     * 【运维信号】这个队列深度 > 0 说明重试也没救回来，需要人工介入。
     */
    @Bean
    public Queue botMentionDlxQueue() {
        return QueueBuilder.durable(BOT_MENTION_DLX_QUEUE).build();
    }

    // ========== QuantaBot 触发链路交换机（C-1）==========

    /**
     * 三个都用 Direct（路由键精确匹配）：本域每种消息只有一条固定路径，
     * 用不到 topic 的通配能力 —— Direct 语义最死板 = 最不易配错。
     */
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

    /**
     * 绑定把"交换机 + 路由键"焊死到队列。这些声明式 Bean 在应用启动时由
     * RabbitAdmin 向 broker 声明（幂等：已存在且参数一致则复用；参数不一致会报错，
     * 客观上防止拓扑漂移）。
     */
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
