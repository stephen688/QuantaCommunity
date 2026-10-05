package com.quanta.demo0.feed.exception;

/**
 * 推荐会话生命周期异常。
 *
 * <p>该异常只表达推荐会话已经过期或被 Redis 清理，HTTP 门面将其转换为 409；
 * 不改变旧推荐接口的通用业务失败语义。</p>
 */
public class RecommendSessionExpiredException extends RuntimeException {

    public static final String MESSAGE = "推荐会话已过期，请刷新";

    public RecommendSessionExpiredException() {
        super(MESSAGE);
    }
}
