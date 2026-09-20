package com.quanta.demo0.properties;

import jakarta.annotation.PostConstruct;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;


/**
 * 搜索热门发现配置
 */
@Component
@ConfigurationProperties(prefix = "quanta.search.trending")
@Data
public class SearchTrendingProperties {

    /**
     * 热门关键词数量
     */
    private Integer keywordLimit = 10;

    /**
     * 热门问题数量
     */
    private Integer questionLimit = 10;

    /**
     * 热门校友数量
     */
    private Integer alumniLimit = 10;

    /**
     * 旧搜索实现的过渡字段；SearchServiceImpl 接入 TrendingCacheService 后删除。
     */
    private Long cacheTtl = 1800L;

    private Long l1TtlSeconds = 10L;

    private Long l1MaximumSize = 1L;

    private Long redisTtlSeconds = 300L;

    private Long redisTtlJitterSeconds = 60L;

    @PostConstruct
    public void validateCacheSettings() {
        if (l1TtlSeconds == null || l1TtlSeconds <= 0) {
            throw new IllegalStateException("quanta.search.trending.l1-ttl-seconds must be positive");
        }
        if (l1MaximumSize == null || l1MaximumSize <= 0) {
            throw new IllegalStateException("quanta.search.trending.l1-maximum-size must be positive");
        }
        if (redisTtlSeconds == null || redisTtlSeconds <= 0) {
            throw new IllegalStateException("quanta.search.trending.redis-ttl-seconds must be positive");
        }
        if (redisTtlJitterSeconds == null || redisTtlJitterSeconds < 0
                || redisTtlJitterSeconds >= redisTtlSeconds) {
            throw new IllegalStateException(
                    "quanta.search.trending.redis-ttl-jitter-seconds must be non-negative and less than redis TTL"
            );
        }
    }

}
