package com.quanta.demo0.platform.security.service;

/**
 * 不依赖 HTTP 请求上下文的用户访问状态服务。
 */
public interface UserAccessStateService {

    boolean canReceiveRealtimePush(Long userId);
}
