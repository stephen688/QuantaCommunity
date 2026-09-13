package com.quanta.demo0.service;

import com.quanta.demo0.security.RateLimitDecision;

/**
 * Redis原子限流服务。
 */
public interface RateLimitService {

    RateLimitDecision check(
            String scene,//限流场景
            String subject,//限流主体
            int limit,//最大请求数
            int windowSeconds,//窗口时间（秒）
            boolean failClosed//是否拒绝失败请求
    );
}
