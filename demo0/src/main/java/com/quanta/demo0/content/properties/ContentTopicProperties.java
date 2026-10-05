package com.quanta.demo0.content.properties;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Max;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

/**
 * 内容主题标签配置。
 *
 * 职责：提供主题标签异步消费者的开关、超时、重试和回填批量边界；
 * 边界：不持有模型客户端，也不改变审核事务。
 *
 * ============================================================
 * 【为什么这些值要做成配置类，而不是写死在 Consumer/Service 里？】
 * ============================================================
 * 这条链路（OutboxDispatcher → content.topic.tag.queue → LLM 打标 → updateTags）
 * 的每个环节都直接花钱、直接占消费者线程，参数必须可运维调整：
 *   - enabled：同时被 ContentTopicTagConsumer 的 @ConditionalOnProperty 读取，
 *     关掉后整个消费者 Bean 消失，消息只堆积在队列里（而不是消费后丢弃）；
 *   - timeoutSeconds / maxTags：约束每次 LLM 调用的时长与产出上限（见 ContentTopicTagServiceImpl）；
 *   - maxRetryCount：消费失败链路的重试上限，超限即 markDead 转死信；
 *   - retryDelaySeconds：Inbox 里的重试期限记账（见下方字段【注意】）；
 *   - maxBackfillBatchSize：回填接口（ContentTopicTagBackfillController → enqueueBackfill）
 *     单批登记上限，防止一次请求把大量帖子塞进 MQ。
 * **LLM 成本类参数要能在不改代码的情况下止血** —— 出问题时先调参，再谈发版。
 *
 * 【@Validated + @Min/@Max 是启动期防线】
 * 配置写错（比如 maxTags=0）会在应用启动时就失败，而不是第一次消费时才炸 ——
 * 失败越早，定位越便宜。
 */
@Data
@Component
@ConfigurationProperties(prefix = "quanta.recommend.topic-tags")
@Validated
public class ContentTopicProperties {

    /** 是否启用主题标签异步处理。 */
    private boolean enabled = true;

    /** 单次模型调用最长等待秒数，避免消费者无限等待。 */
    @Min(1)
    private long timeoutSeconds = 20L;

    /** 每帖最多保存的主题标签数量。 */
    @Min(1)
    @Max(3)
    private int maxTags = 3;

    /** 消费失败后的最大重试次数。 */
    @Min(0)
    private int maxRetryCount = 3;

    /** 重试消息在重试队列中停留的秒数。 */
    /**
     * 【注意】重投的真实延迟由重试队列的 x-message-ttl（见 TopicTagMQConfig，
     * 队列级固定 60000ms）决定，本值主要作为 Inbox 中的重试期限记账；
     * 调整重试节奏时两处需同步，否则"约定的重试窗口"和"实际投递节奏"会对不上。
     */
    @Min(1)
    private long retryDelaySeconds = 60L;

    /** 回填接口单批上限。 */
    @Min(1)
    private int maxBackfillBatchSize = 100;
}
