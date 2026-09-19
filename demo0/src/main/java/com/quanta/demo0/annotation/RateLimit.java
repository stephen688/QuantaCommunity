package com.quanta.demo0.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 当前登录用户维度的接口限流。
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface RateLimit {

    String scene();

    int limit();

    int windowSeconds();

    /**
     * BOT 角色独立配额（次/窗口）。
     * -1 = 不区分（默认，BOT 与普通用户同档）；
     * >= 0 时 BOT 角色自动改用 scene + "-bot" 与本配额（C-5 契约）。
     */
    int botLimit() default -1;

    /**
     * Redis异常时是否拒绝请求。
     */
    boolean failClosed() default true;
}
