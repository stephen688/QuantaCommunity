package com.quanta.demo0.security;

/**
 * HTTP 和 WebSocket 共用的 Token 认证服务。
 */
public interface TokenAuthenticationService {

    /**
     * 校验 Token 并返回当前用户安全信息。
     *
     * @param token 请求携带的 JWT
     * @return 已认证用户
     * @throws TokenAuthenticationException 认证失败
     */
    AuthenticatedUser authenticate(String token);
}