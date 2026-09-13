package com.quanta.demo0.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.List;

@Data
@Component
@ConfigurationProperties(prefix = "quanta.outbox.dispatch")
public class OutboxDispatchProperties {

    private long fixedDelayMs = 1000;

    private int batchSize = 50;

    private long leaseSeconds = 60;

    private long confirmTimeoutSeconds = 5;

    /**
     * 表示第一次发送失败后，最多再重试 6 次。
     */
    private int maxRetryCount = 6;

    private List<Long> retryDelaysSeconds = List.of(
            5L,
            15L,
            60L,
            300L,
            900L,
            1800L
    );
}
