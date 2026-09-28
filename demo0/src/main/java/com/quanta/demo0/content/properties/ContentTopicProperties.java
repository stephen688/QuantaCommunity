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
    @Min(1)
    private long retryDelaySeconds = 60L;

    /** 回填接口单批上限。 */
    @Min(1)
    private int maxBackfillBatchSize = 100;
}
