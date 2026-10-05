package com.quanta.demo0.platform.security.service.impl;

import com.quanta.demo0.platform.redis.constant.RedisConstants;
import com.quanta.demo0.platform.security.constant.JwtClaimsConstant;
import com.quanta.demo0.platform.security.exception.AuthFailedException;
import com.quanta.demo0.platform.security.properties.JwtProperties;
import com.quanta.demo0.platform.security.service.SessionService;
import com.quanta.demo0.platform.security.utils.JwtUtil;
import com.quanta.demo0.user.vo.UserAccountVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static com.quanta.demo0.platform.redis.constant.RedisConstants.LOGIN_USER_KEY;
import static com.quanta.demo0.platform.redis.constant.RedisConstants.LOGIN_USER_TTL;

/**
 * JWT/Redis 会话服务实现。
 *
 * 该类只负责会话令牌和会话存储，不读取用户数据库；账号封禁状态由登录入口传入的
 * UserAccountVO 快照判定，后续请求则由 OptionalJwtAuthenticationFilter/UserAccessStateService 复核。
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class SessionServiceImpl implements SessionService {

    private final JwtProperties jwtProperties;
    private final StringRedisTemplate stringRedisTemplate;
    // 比较与删除在 Redis 同一原子操作内，避免旧退出请求删除并发登录的新令牌。
    private static final DefaultRedisScript<Long> REVOKE_SESSION = new DefaultRedisScript<>(
            "if redis.call('GET', KEYS[1]) == ARGV[1] then return redis.call('DEL', KEYS[1]) else return 0 end", Long.class);

    /** {@inheritDoc} */
    @Override
    public void revokeSession(Long userId, String token) {
        if (userId == null || token == null || token.isBlank()) throw new AuthFailedException("登录会话无效");
        stringRedisTemplate.execute(REVOKE_SESSION, List.of(LOGIN_USER_KEY + userId), token);
    }

    /**
     * 为用户签发 JWT 并保存登录态。
     */
    @Override
    public String login(UserAccountVO user) {
        if (user == null || user.getId() == null) {
            throw new AuthFailedException("登录用户不存在");
        }
        if (Integer.valueOf(1).equals(user.getAccountStatus())) {
            throw new AuthFailedException("账号已被封禁");
        }

        Map<String, Object> claims = new HashMap<>();
        claims.put(JwtClaimsConstant.USER_ID, user.getId());
        String token = JwtUtil.createJWT(
                jwtProperties.getUserSecretKey(),
                jwtProperties.getUserTtl(),
                claims
        );
        String loginKey = LOGIN_USER_KEY + user.getId();
        stringRedisTemplate.opsForValue().set(loginKey, token, LOGIN_USER_TTL, TimeUnit.DAYS);
        return token;
    }

    /**
     * 删除当前用户的登录令牌和封禁标记；保持历史接口的容错语义。
     */
    @Override
    public void logout() {
        try {
            Long userId = com.quanta.demo0.platform.security.context.BaseContext.getCurrentId();
            stringRedisTemplate.delete(LOGIN_USER_KEY + userId);
            stringRedisTemplate.delete(RedisConstants.USER_BANNED_KEY + userId);
            log.info("退出登录成功，userId={}", userId);
        } catch (Exception exception) {
            log.error("退出登录失败，token 无效或已过期", exception);
        }
    }
}
