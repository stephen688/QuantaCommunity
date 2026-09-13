package com.quanta.demo0.security;

import com.quanta.demo0.constant.JwtClaimsConstant;
import com.quanta.demo0.constant.RedisConstants;
import com.quanta.demo0.constant.RoleConstants;
import com.quanta.demo0.entity.User;
import com.quanta.demo0.entity.UserAuth;
import com.quanta.demo0.enums.AuditStatus;
import com.quanta.demo0.mapper.UserMapper;
import com.quanta.demo0.mapper.UserRoleMapper;
import com.quanta.demo0.properties.JwtProperties;
import com.quanta.demo0.utils.JwtUtil;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.TimeUnit;

import static com.quanta.demo0.constant.RedisConstants.LOGIN_USER_KEY;

/**
 * Token 认证服务实现类。
 *
 * 核心职责：
 * 1. 校验 JWT 签名和有效期；
 * 2. 校验 Redis 当前有效 Token；
 * 3. 校验用户是否存在、是否被封禁；
 * 4. 加载校友认证状态和兼容角色信息。
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
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private UserMapper userMapper;
    /**
     * 用户管理角色查询Mapper。
     */
    @Autowired
    private UserRoleMapper userRoleMapper;
    @Override
    public AuthenticatedUser authenticate(String token) {
        if (token == null || token.isBlank()) {
            throw authenticationFailed(
                    TokenAuthenticationFailureReason.TOKEN_MISSING,
                    "未携带登录凭证"
            );
        }

        // 1. 校验 JWT 并取得用户 ID
        Long userId = parseUserId(token);

        // 2. 校验 Redis 中的当前有效 Token
        validateCurrentSession(userId, token);

        // 3. Redis 封禁标记优先判断
        String bannedKey = RedisConstants.USER_BANNED_KEY + userId;
        Boolean banned = stringRedisTemplate.hasKey(bannedKey);
        if (Boolean.TRUE.equals(banned)) {
            throw authenticationFailed(
                    TokenAuthenticationFailureReason.USER_BANNED,
                    "账号已被封禁"
            );
        }

        // 4. 数据库是账号状态和管理员状态的事实源
        User user = userMapper.getById(userId);
        if (user == null) {
            throw authenticationFailed(
                    TokenAuthenticationFailureReason.USER_NOT_FOUND,
                    "用户不存在"
            );
        }

        if (Integer.valueOf(1).equals(user.getAccountStatus())) {
            throw authenticationFailed(
                    TokenAuthenticationFailureReason.USER_BANNED,
                    "账号已被封禁"
            );
        }

        // 5. 加载校友认证状态
        boolean verified = loadVerifiedStatus(userId);

        // 6. 加载用户的系统角色和数据库管理角色
        Set<String> roles = loadRoles(userId, verified);

// 7. 根据角色计算用户拥有的具体权限
        Set<String> authorities =
                RolePermissionMapping.permissionsFor(roles);

        /*
         * SUPER_ADMIN不再直接读取旧is_admin字段，
         * 而是以user_role表中的角色记录为准。
         */
        boolean superAdmin =
                roles.contains(RoleConstants.SUPER_ADMIN);

        return AuthenticatedUser.builder()
                .userId(userId)
                .roles(roles)
                .authorities(authorities)
                .accountStatus(user.getAccountStatus())
                .verified(verified)
                .admin(superAdmin)
                .build();
    }

    /**
     * 解析 JWT 中的用户 ID。
     */
    private Long parseUserId(String token) {
        try {
            Claims claims = JwtUtil.parseJWT(
                    jwtProperties.getUserSecretKey(),
                    token
            );

            Object userIdClaim = claims.get(JwtClaimsConstant.USER_ID);
            if (userIdClaim == null) {
                throw new IllegalArgumentException("JWT 缺少 userId");
            }

            return Long.valueOf(userIdClaim.toString());
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
     * 使用 cache-aside 模式加载校友认证状态。
     */
    private boolean loadVerifiedStatus(Long userId) {
        String cacheKey = RedisConstants.SECURITY_VERIFIED_KEY + userId;
        String cachedStatus =
                stringRedisTemplate.opsForValue().get(cacheKey);

        if ("1".equals(cachedStatus)) {
            return true;
        }
        if ("0".equals(cachedStatus)) {
            return false;
        }

        // 缓存不存在时回源数据库
        UserAuth userAuth = userMapper.getUserAuthByUserId(userId);
        boolean verified = userAuth != null
                && Objects.equals(
                userAuth.getAuditStatus(),
                AuditStatus.APPROVED.getCode()
        );

        stringRedisTemplate.opsForValue().set(
                cacheKey,
                verified ? "1" : "0",
                RedisConstants.SECURITY_VERIFIED_TTL_MINUTES,
                TimeUnit.MINUTES
        );

        return verified;
    }

    /**
     * 加载当前用户拥有的全部角色。
     *
     * 角色有两个来源：
     * 1. USER和VERIFIED_USER由系统状态自动生成；
     * 2. 管理角色从user_role表读取。
     */
    private Set<String> loadRoles(
            Long userId,
            boolean verified
    ) {
        Set<String> roles = new HashSet<>();

        /*
         * Token认证成功的用户自动拥有USER角色。
         */
        roles.add(RoleConstants.USER);

        /*
         * 校友身份审核通过后自动拥有VERIFIED_USER角色。
         */
        if (verified) {
            roles.add(RoleConstants.VERIFIED_USER);
        }

        /*
         * 从数据库读取人工授予的管理角色。
         */
        List<String> assignedRoles =
                userRoleMapper.findRoleCodesByUserId(userId);

        if (assignedRoles != null) {
            for (String assignedRole : assignedRoles) {

                /*
                 * 只接受系统明确支持的管理角色，
                 * 未知角色不会进入Spring Security。
                 */
                if (RolePermissionMapping.isManagementRole(
                        assignedRole
                )) {
                    roles.add(assignedRole);
                } else {
                    log.warn(
                            "忽略未知用户角色，userId={}，role={}",
                            userId,
                            assignedRole
                    );
                }
            }
        }

        return Collections.unmodifiableSet(roles);
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