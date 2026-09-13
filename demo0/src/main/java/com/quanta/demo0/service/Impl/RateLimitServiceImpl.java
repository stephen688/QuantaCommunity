package com.quanta.demo0.service.Impl;

import com.quanta.demo0.security.RateLimitDecision;
import com.quanta.demo0.service.RateLimitService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Redis Lua限流服务实现类。
 */
@Service
@Slf4j
public class RateLimitServiceImpl
        implements RateLimitService {

    private static final DefaultRedisScript<List>
            RATE_LIMIT_SCRIPT;

    static {
        RATE_LIMIT_SCRIPT = new DefaultRedisScript<>();
        RATE_LIMIT_SCRIPT.setLocation(
                new ClassPathResource("lua/rate_limit.lua")
        );
        RATE_LIMIT_SCRIPT.setResultType(List.class);
    }

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public RateLimitDecision check(
            String scene,
            String subject,
            int limit,
            int windowSeconds,
            boolean failClosed
    ) {
        String key = "security:rate-limit:"
                + scene + ":" + subject;

        try {
            List<Long> result = stringRedisTemplate.execute(
                    RATE_LIMIT_SCRIPT,
                    List.of(key),
                    String.valueOf(windowSeconds),
                    String.valueOf(limit)
            );

            if (result == null || result.size() < 3) {
                throw new IllegalStateException("Redis限流结果格式错误");
            }

            return new RateLimitDecision(
                    result.get(0) == 1L,
                    result.get(1),
                    Math.max(result.get(2), 1L)
            );
        } catch (Exception exception) {
            log.error("Redis限流检查失败，scene={}", scene, exception);

            if (failClosed) {
                return new RateLimitDecision(false, 0, 1);
            }

            return new RateLimitDecision(true, limit, 0);
        }
    }
}
