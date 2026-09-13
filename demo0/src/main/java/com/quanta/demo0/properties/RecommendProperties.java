package com.quanta.demo0.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.Min;

/**
 * 推荐流配置：启动时 Redis 冷启动预热等。
 */
@Data
@Component
@ConfigurationProperties(prefix = "quanta.recommend")
@Validated
public class RecommendProperties {

    /**
     * 启动时若 Redis 推荐 ZSET 为空，则从 MySQL 已通过内容预热。
     */
    private boolean warmupOnStartup = true;

    @Min(1)
    private int warmupBatchSize = 100;
}
