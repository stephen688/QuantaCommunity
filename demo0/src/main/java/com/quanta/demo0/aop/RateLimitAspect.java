package com.quanta.demo0.aop;

import com.quanta.demo0.annotation.RateLimit;
import com.quanta.demo0.constant.RoleConstants;
import com.quanta.demo0.platform.security.exception.RateLimitExceededException;
import com.quanta.demo0.platform.security.model.AuthenticatedUser;
import com.quanta.demo0.security.RateLimitDecision;
import com.quanta.demo0.service.RateLimitService;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * 注解式接口限流切面。
 * 只用于HTTP用户入口，禁止加到MQ、定时任务和内部Service上。
 */
@Aspect
@Component
@Slf4j
public class RateLimitAspect {

    @Autowired
    private RateLimitService rateLimitService;

    @Around("@annotation(rateLimit)")
    public Object checkRateLimit(
            ProceedingJoinPoint joinPoint,
            RateLimit rateLimit
    ) throws Throwable {
        // ① 从SecurityContext取当前登录用户
        Authentication authentication =
                SecurityContextHolder
                        .getContext()
                        .getAuthentication();

        if (authentication == null
                || !(authentication.getPrincipal()
                instanceof AuthenticatedUser authenticatedUser)) {
            throw new AuthenticationCredentialsNotFoundException(
                    "当前用户未登录"
            );
        }

        // ② BOT 角色且配置独立配额时切换到独立限流场景
        String scene = rateLimit.scene();
        int limit = rateLimit.limit();
        if (rateLimit.botLimit() >= 0
                && authenticatedUser.getRoles() != null
                && authenticatedUser.getRoles().contains(RoleConstants.BOT)) {
            scene = scene + "-bot";
            limit = rateLimit.botLimit();
        }

        // ③ 用用户ID做限流主体，执行原子限流
        RateLimitDecision decision = rateLimitService.check(
                scene,
                String.valueOf(authenticatedUser.getUserId()),
                limit,
                rateLimit.windowSeconds(),
                rateLimit.failClosed()
        );

        // ④ 被限流就抛429异常，否则继续执行业务
        if (!decision.isAllowed()) {
            throw new RateLimitExceededException(
                    "操作过于频繁，请稍后再试",
                    decision.getRetryAfterSeconds()
            );
        }

        return joinPoint.proceed();
    }
}
