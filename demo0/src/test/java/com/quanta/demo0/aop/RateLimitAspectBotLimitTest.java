package com.quanta.demo0.aop;

import com.quanta.demo0.annotation.RateLimit;
import com.quanta.demo0.constant.RoleConstants;
import com.quanta.demo0.platform.security.model.AuthenticatedUser;
import com.quanta.demo0.security.RateLimitDecision;
import com.quanta.demo0.service.RateLimitService;
import org.aspectj.lang.ProceedingJoinPoint;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * C-5 限流分档：BOT 角色走独立 scene（comment-send-bot）与独立配额（botLimit）。
 */
@ExtendWith(MockitoExtension.class)
class RateLimitAspectBotLimitTest {

    @Mock
    private RateLimitService rateLimitService;

    @Mock
    private ProceedingJoinPoint joinPoint;

    @Mock
    private RateLimitDecision allowedDecision;

    @InjectMocks
    private RateLimitAspect aspect;

    private final RateLimit annotation = new RateLimit() {
        @Override
        public Class<? extends java.lang.annotation.Annotation> annotationType() {
            return RateLimit.class;
        }

        @Override
        public String scene() {
            return "comment-send";
        }

        @Override
        public int limit() {
            return 10;
        }

        @Override
        public int windowSeconds() {
            return 60;
        }

        @Override
        public boolean failClosed() {
            return true;
        }

        @Override
        public int botLimit() {
            return 6;
        }
    };

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private void authenticate(long userId, String... roles) {
        AuthenticatedUser user = AuthenticatedUser.builder()
                .userId(userId)
                .roles(Set.of(roles))
                .build();
        UsernamePasswordAuthenticationToken authentication =
                new UsernamePasswordAuthenticationToken(
                        user,
                        null,
                        List.of()
                );
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }

    private RateLimit annotationWithoutBotLimit() {
        return new RateLimit() {
            @Override
            public Class<? extends java.lang.annotation.Annotation> annotationType() {
                return RateLimit.class;
            }

            @Override
            public String scene() {
                return "comment-report";
            }

            @Override
            public int limit() {
                return 5;
            }

            @Override
            public int windowSeconds() {
                return 60;
            }

            @Override
            public boolean failClosed() {
                return true;
            }

            @Override
            public int botLimit() {
                return -1;
            }
        };
    }

    @Test
    void bot用户走botScene与botLimit() throws Throwable {
        authenticate(10000L, "USER", RoleConstants.BOT);
        when(rateLimitService.check(
                eq("comment-send-bot"),
                eq("10000"),
                eq(6),
                eq(60),
                eq(true)
        )).thenReturn(allowedDecision);
        when(allowedDecision.isAllowed()).thenReturn(true);
        when(joinPoint.proceed()).thenReturn("ok");

        Object result = aspect.checkRateLimit(joinPoint, annotation);

        assertSame("ok", result);
        verify(rateLimitService).check(
                "comment-send-bot", "10000", 6, 60, true
        );
    }

    @Test
    void 普通用户仍走原scene与limit() throws Throwable {
        authenticate(3L, "USER", "VERIFIED_USER");
        when(rateLimitService.check(
                eq("comment-send"),
                eq("3"),
                eq(10),
                eq(60),
                eq(true)
        )).thenReturn(allowedDecision);
        when(allowedDecision.isAllowed()).thenReturn(true);
        when(joinPoint.proceed()).thenReturn("ok");

        aspect.checkRateLimit(joinPoint, annotation);

        verify(rateLimitService).check(
                "comment-send", "3", 10, 60, true
        );
    }

    @Test
    void 未配置botLimit时不分档() throws Throwable {
        authenticate(10000L, "USER", RoleConstants.BOT);
        when(rateLimitService.check(
                eq("comment-report"),
                eq("10000"),
                eq(5),
                eq(60),
                eq(true)
        )).thenReturn(allowedDecision);
        when(allowedDecision.isAllowed()).thenReturn(true);
        when(joinPoint.proceed()).thenReturn("ok");

        aspect.checkRateLimit(
                joinPoint,
                annotationWithoutBotLimit()
        );

        verify(rateLimitService).check(
                "comment-report", "10000", 5, 60, true
        );
    }
}
