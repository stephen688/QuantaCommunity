package com.quanta.demo0.security;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * 一次限流判断结果。
 */
@Data
@AllArgsConstructor
public class RateLimitDecision {

    private boolean allowed;
    private long remaining;
    private long retryAfterSeconds;
}
