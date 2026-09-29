package com.quanta.demo0.platform.security.exception;

import com.quanta.demo0.platform.security.enums.TokenAuthenticationFailureReason;
import com.quanta.demo0.platform.security.exception.AuthFailedException;

/**
 * Token 认证异常。
 *
 * 继续继承项目现有的 AuthFailedException，
 * 保持和当前异常体系风格一致。
 */
public class TokenAuthenticationException extends AuthFailedException {

    /**
     * 认证失败的具体原因。
     */
    private final TokenAuthenticationFailureReason reason;

    public TokenAuthenticationException(
            TokenAuthenticationFailureReason reason,
            String message
    ) {
        super(message);
        this.reason = reason;
    }

    public TokenAuthenticationFailureReason getReason() {
        return reason;
    }
}
