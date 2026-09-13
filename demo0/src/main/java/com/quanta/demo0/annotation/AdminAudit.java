package com.quanta.demo0.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 管理员高风险操作审计标记。
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface AdminAudit {

    String action();

    String targetType();

    /**
     * 目标ID的SpEL表达式，例如#userId。
     */
    String targetId() default "";
}
