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
     * Redis异常时是否拒绝请求。
     */
    boolean failClosed() default true;
}
