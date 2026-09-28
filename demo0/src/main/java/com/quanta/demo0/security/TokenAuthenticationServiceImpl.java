package com.quanta.demo0.security;

import com.quanta.demo0.constant.JwtClaimsConstant;
import com.quanta.demo0.constant.RedisConstants;
import com.quanta.demo0.platform.security.model.AuthenticatedUser;
import com.quanta.demo0.platform.security.model.AuthenticationSnapshot;
import com.quanta.demo0.properties.JwtProperties;
import com.quanta.demo0.properties.QuantabotProperties;
import com.quanta.demo0.utils.JwtUtil;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import static com.quanta.demo0.constant.RedisConstants.LOGIN_USER_KEY;

/**
 * Token 认证服务实现类。
 *
 * 核心职责：
 * 1. 校验 JWT 签名和有效期；
 * 2. 校验 Redis 当前有效 Token；
 * 3. 校验用户是否存在、是否被封禁；
 * 4. 从短 TTL 安全快照读取账号状态、校友认证状态和兼容角色信息。
 *
 * HTTP 和 WebSocket 必须共用该服务，
 * 避免两套认证逻辑长期不一致。
 */
@Service
@Slf4j
public class TokenAuthenticationServiceImpl
        implements TokenAuthenticationService {

    @Autowired
    private JwtProperties jwtProperties;

    @Autowired
    private QuantabotProperties quantabotProperties;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    /**
     * 数据库-backed 认证快照缓存。JWT、登录态和封禁标记仍在本服务逐次校验。
     */
    @Autowired
    private AuthenticationSnapshotCache authenticationSnapshotCache;
    @Override
    public AuthenticatedUser authenticate(String token) {
        if (token == null || token.isBlank()) {
            throw authenticationFailed(
                    TokenAuthenticationFailureReason.TOKEN_MISSING,
                    "未携带登录凭证"
            );
        }

        // 1. 校验 JWT 并取得用户 ID 与令牌类型
        Claims claims = parseClaims(token);
        Long userId = extractUserId(claims);

        // 2. service token 仅限 bot 系统账号，跳过 Redis 会话校验。
        boolean serviceToken = JwtClaimsConstant.SERVICE_TOKEN_TYPE
                .equals(claims.get(JwtClaimsConstant.TOKEN_TYPE));
        if (serviceToken) {
            if (!quantabotProperties.getBotUserId().equals(userId)) {
                throw authenticationFailed(
                        TokenAuthenticationFailureReason.TOKEN_INVALID,
                        "service token 仅限 bot 系统账号"
                );
            }
        } else {
            // 3. 普通用户 token 仍校验 Redis 中的当前有效会话
            validateCurrentSession(userId, token);
        }

        // 3. Redis 封禁标记优先判断
        String bannedKey = RedisConstants.USER_BANNED_KEY + userId;
        Boolean banned = stringRedisTemplate.hasKey(bannedKey);
        if (Boolean.TRUE.equals(banned)) {
            throw authenticationFailed(
                    TokenAuthenticationFailureReason.USER_BANNED,
                    "账号已被封禁"
            );
        }

        // 4. 读取数据库-backed 安全快照；未知用户不会进入缓存。
        AuthenticationSnapshot snapshot =
                authenticationSnapshotCache.get(userId, serviceToken);
        if (snapshot == null) {
            throw authenticationFailed(
                    TokenAuthenticationFailureReason.USER_NOT_FOUND,
                    "用户不存在"
            );
        }

        if (Integer.valueOf(1).equals(snapshot.accountStatus())) {
            throw authenticationFailed(
                    TokenAuthenticationFailureReason.USER_BANNED,
                    "账号已被封禁"
            );
        }

        // 每次认证都新建认证主体，缓存只保存不可变快照。
        return AuthenticatedUser.builder()
                .userId(userId)
                .roles(snapshot.roles())
                .authorities(snapshot.authorities())
                .accountStatus(snapshot.accountStatus())
                .verified(snapshot.verified())
                .admin(snapshot.admin())
                .build();
    }

    /**
     * 解析 JWT 全部声明。
     */
    private Claims parseClaims(String token) {
        try {
            return JwtUtil.parseJWT(
                    jwtProperties.getUserSecretKey(),
                    token
            );
        } catch (ExpiredJwtException exception) {
            throw authenticationFailed(
                    TokenAuthenticationFailureReason.TOKEN_EXPIRED,
                    "登录凭证已过期"
            );
        } catch (JwtException | IllegalArgumentException exception) {
            throw authenticationFailed(
                    TokenAuthenticationFailureReason.TOKEN_INVALID,
                    "登录凭证无效"
            );
        }
    }

    /**
     * 从声明中提取用户 ID。
     */
    private Long extractUserId(Claims claims) {
        Object userIdClaim = claims.get(JwtClaimsConstant.USER_ID);
        if (userIdClaim == null) {
            throw authenticationFailed(
                    TokenAuthenticationFailureReason.TOKEN_INVALID,
                    "JWT 缺少 userId"
            );
        }

        try {
            return Long.valueOf(userIdClaim.toString());
        } catch (NumberFormatException exception) {
            throw authenticationFailed(
                    TokenAuthenticationFailureReason.TOKEN_INVALID,
                    "JWT userId 非法"
            );
        }
    }

    /**
     * 校验请求 Token 是否仍然是 Redis 中的当前有效 Token。
     */
    private void validateCurrentSession(Long userId, String token) {
        String loginKey = LOGIN_USER_KEY + userId;
        String redisToken = stringRedisTemplate.opsForValue().get(loginKey);

        if (redisToken == null || redisToken.isBlank()) {
            throw authenticationFailed(
                    TokenAuthenticationFailureReason.SESSION_NOT_FOUND,
                    "登录状态已失效"
            );
        }

        if (!token.equals(redisToken)) {
            throw authenticationFailed(
                    TokenAuthenticationFailureReason.SESSION_MISMATCH,
                    "账号已在其他位置重新登录"
            );
        }
    }

    /**
     * 统一创建认证异常，并记录不包含 Token 的安全日志。
     */
    private TokenAuthenticationException authenticationFailed(
            TokenAuthenticationFailureReason reason,
            String message
    ) {
        log.warn("Token 认证失败，reason={}", reason);
        return new TokenAuthenticationException(reason, message);
    }
}
