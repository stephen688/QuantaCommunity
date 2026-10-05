package com.quanta.demo0.search.properties;

import jakarta.annotation.PostConstruct;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;


/**
 * 搜索热门发现配置
 *
 * <p>绑定 application.yml 的 quanta.search.trending 前缀，是热榜读链路
 * （TrendingCacheServiceImpl 两级缓存 + TrendingDataLoader 各榜单条数）的唯一参数来源。
 * Java 代码里的默认值与 yml 中的显式值保持一致：
 * keyword/question/alumni-limit=10、l1-ttl-seconds=10、l1-maximum-size=1、
 * redis-ttl-seconds=300、redis-ttl-jitter-seconds=60。</p>
 *
 * <p>【边界】yml 同前缀下还有 rebuild-follower-rank-on-startup: false（启动时
 * 是否重建粉丝排行，首次部署手动开一次），本类未绑定该字段，当前无 Java 代码读取。</p>
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
     * L1（Caffeine 本地缓存）过期秒数，默认 10。
     * 刻意超短：跨实例失效只清发起方 L1，其他实例最迟 10s 自愈，
     * 用一点旧数据容忍度换掉广播失效的复杂度。
     */
    private Long l1TtlSeconds = 10L;

    /**
     * L1 最大条目数，默认 1。热榜全站就一份聚合结果（单 key），
     * 存 1 条足够；调大没有意义，调小到 0 会被校验拒绝。
     */
    private Long l1MaximumSize = 1L;

    /**
     * L2（Redis key=search:trending:all）基础 TTL 秒数，默认 300。
     * 要足够长才能替 MySQL 扛住热榜读流量。
     */
    private Long redisTtlSeconds = 300L;

    /**
     * L2 TTL 随机抖动幅度（±），默认 60。实际 TTL = 300 + U(-60, 60) ∈ [240, 360]，
     * 防止多实例同时回填的缓存同时到期、瞬间集体回源打崩 MySQL（缓存雪崩）。
     * 必须小于 redis-ttl-seconds（见 {@link #validateCacheSettings()}）。
     */
    private Long redisTtlJitterSeconds = 60L;

    /**
     * 启动期 fail-fast 校验：TTL 类配置必须为正，抖动必须非负且严格小于基础 TTL
     * （否则实际 TTL 可能被抖成 0 或负数）。配置错误直接阻止应用启动，而不是运行期才暴露。
     */
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
