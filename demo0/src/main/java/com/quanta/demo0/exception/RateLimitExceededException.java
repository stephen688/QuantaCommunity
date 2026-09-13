package com.quanta.demo0.exception;

import lombok.Getter;

/**
 * 请求超过安全限流阈值。
 */
@Getter
public class RateLimitExceededException
        extends RuntimeException {

    private final long retryAfterSeconds;

    public RateLimitExceededException(
            String message,
            long retryAfterSeconds
    ) {
        super(message);
        this.retryAfterSeconds = retryAfterSeconds;
    }
}
