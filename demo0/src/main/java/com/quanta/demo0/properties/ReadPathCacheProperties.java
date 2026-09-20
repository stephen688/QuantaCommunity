package com.quanta.demo0.properties;

import jakarta.annotation.PostConstruct;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "quanta.cache.read-path")
@Data
public class ReadPathCacheProperties {

    private LocalCache authentication = new LocalCache(10_000L, 45L);
    private LocalCache author = new LocalCache(10_000L, 180L);
    private ContentDetailCache contentDetail = new ContentDetailCache();

    @PostConstruct
    public void validate() {
        validateLocalCache("authentication", authentication);
        validateLocalCache("author", author);

        requirePositive("content-detail.l1-maximum-size", contentDetail.l1MaximumSize);
        requirePositive("content-detail.l1-ttl-seconds", contentDetail.l1TtlSeconds);
        requirePositive("content-detail.redis-ttl-seconds", contentDetail.redisTtlSeconds);
        requirePositive("content-detail.negative-ttl-seconds", contentDetail.negativeTtlSeconds);
        requirePositive("content-detail.tombstone-ttl-seconds", contentDetail.tombstoneTtlSeconds);
        if (contentDetail.redisTtlJitterSeconds == null
                || contentDetail.redisTtlJitterSeconds < 0
                || contentDetail.redisTtlJitterSeconds >= contentDetail.redisTtlSeconds) {
            throw new IllegalStateException(
                    "quanta.cache.read-path.content-detail.redis-ttl-jitter-seconds must be non-negative and less than redis TTL"
            );
        }
    }

    private void validateLocalCache(String name, LocalCache cache) {
        if (cache == null) {
            throw new IllegalStateException("quanta.cache.read-path." + name + " is required");
        }
        requirePositive(name + ".maximum-size", cache.maximumSize);
        requirePositive(name + ".ttl-seconds", cache.ttlSeconds);
    }

    private void requirePositive(String name, Long value) {
        if (value == null || value <= 0) {
            throw new IllegalStateException("quanta.cache.read-path." + name + " must be positive");
        }
    }

    @Data
    public static class LocalCache {
        private Long maximumSize;
        private Long ttlSeconds;

        public LocalCache() {
        }

        public LocalCache(Long maximumSize, Long ttlSeconds) {
            this.maximumSize = maximumSize;
            this.ttlSeconds = ttlSeconds;
        }
    }

    @Data
    public static class ContentDetailCache {
        private Long l1MaximumSize = 10_000L;
        private Long l1TtlSeconds = 30L;
        private Long redisTtlSeconds = 300L;
        private Long redisTtlJitterSeconds = 60L;
        private Long negativeTtlSeconds = 60L;
        private Long tombstoneTtlSeconds = 360L;
    }
}
