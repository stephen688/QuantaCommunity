package com.quanta.demo0.security;

/**
 * Token 认证失败原因。
 *
 * 该枚举主要用于服务端日志、测试和后续统一异常处理，
 * 不直接把详细原因全部暴露给客户端。
 */
public enum TokenAuthenticationFailureReason {

    /**
     * 请求没有携带 Token。
     */
    TOKEN_MISSING,

    /**
     * Token 签名错误、格式错误或者缺少用户 ID。
     */
    TOKEN_INVALID,

    /**
     * Token 已经过期。
     */
    TOKEN_EXPIRED,

    /**
     * Redis 中已经不存在该用户的登录态。
     */
    SESSION_NOT_FOUND,

    /**
     * 请求 Token 与 Redis 当前 Token 不一致。
     */
    SESSION_MISMATCH,

    /**
     * Token 对应的用户已经不存在。
     */
    USER_NOT_FOUND,

    /**
     * 用户已经被封禁。
     */
    USER_BANNED
}