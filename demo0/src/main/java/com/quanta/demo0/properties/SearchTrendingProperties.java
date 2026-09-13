package com.quanta.demo0.properties;

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
     * 缓存过期时间（秒）
     */
    private Long cacheTtl = 1800L;
}