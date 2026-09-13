package com.quanta.demo0.service;

/**
 * 用户访问状态服务。
 * 该服务不依赖HTTP请求、Token、BaseContext或SecurityContext，
 * 可以安全地被MQ消费者和内部任务调用。
 */
public interface UserAccessStateService {

    /**
     * 判断用户是否可以接收WebSocket实时推送。
     *
     * @param userId 接收通知的用户ID
     * @return true-允许推送，false-跳过推送
     */
    boolean canReceiveRealtimePush(Long userId);
}
