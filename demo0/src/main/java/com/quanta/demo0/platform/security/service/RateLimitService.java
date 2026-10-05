package com.quanta.demo0.platform.security.service;

import com.quanta.demo0.platform.security.model.RateLimitDecision;

/**
 * Redis原子限流服务。
 */
public interface RateLimitService {

    RateLimitDecision check(
            String scene,//限流场景：评论、点赞、收藏、关注、取消关注、发送消息、接收消息
            String subject,//限流主体：用户ID
            int limit,//最大请求数：次/窗口时间
            int windowSeconds,//窗口时间（秒），指的是每个窗口的时间间隔
            boolean failClosed//是否拒绝失败请求
    );
}
