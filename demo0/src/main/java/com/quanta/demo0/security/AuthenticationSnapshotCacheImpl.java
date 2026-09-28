package com.quanta.demo0.security;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.quanta.demo0.constant.RoleConstants;
import com.quanta.demo0.entity.User;
import com.quanta.demo0.entity.UserAuth;
import com.quanta.demo0.enums.AuditStatus;
import com.quanta.demo0.mapper.UserMapper;
import com.quanta.demo0.mapper.UserRoleMapper;
import com.quanta.demo0.platform.security.model.AuthenticationSnapshot;
import com.quanta.demo0.properties.QuantabotProperties;
import com.quanta.demo0.properties.ReadPathCacheProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Caffeine implementation of the database-backed authentication snapshot.
 *
 * <p>The cache key includes the token class. This is important for the Bot
 * service identity: a normal token for the same account must never reuse a
 * snapshot containing {@code BOT}.</p>
 */
@Service
@Slf4j
public class AuthenticationSnapshotCacheImpl
        implements AuthenticationSnapshotCache {

    private final UserMapper userMapper;
    private final UserRoleMapper userRoleMapper;
    private final QuantabotProperties quantabotProperties;
    private final Cache<AuthenticationCacheKey, AuthenticationSnapshot> cache;

    public AuthenticationSnapshotCacheImpl(
            UserMapper userMapper,
            UserRoleMapper userRoleMapper,
            QuantabotProperties quantabotProperties,
            ReadPathCacheProperties cacheProperties
    ) {
        this.userMapper = userMapper;
        this.userRoleMapper = userRoleMapper;
        this.quantabotProperties = quantabotProperties;

        ReadPathCacheProperties.LocalCache authentication =
                cacheProperties.getAuthentication();
        this.cache = Caffeine.newBuilder()
                .maximumSize(authentication.getMaximumSize())
                .expireAfterWrite(
                        Duration.ofSeconds(authentication.getTtlSeconds())
                )
                .build();
    }

    @Override
    public AuthenticationSnapshot get(Long userId, boolean serviceToken) {
        Objects.requireNonNull(userId, "userId must not be null");

        AuthenticationCacheKey key =
                new AuthenticationCacheKey(userId, serviceToken);
        return cache.get(key, ignored -> load(userId, serviceToken));
    }

    @Override
    public void evict(Long userId) {
        if (userId == null) {
            return;
        }
        cache.invalidate(new AuthenticationCacheKey(userId, false));
        cache.invalidate(new AuthenticationCacheKey(userId, true));
    }

    private AuthenticationSnapshot load(Long userId, boolean serviceToken) {
        User user = userMapper.getById(userId);
        if (user == null) {
            return null;
        }

        UserAuth userAuth = userMapper.getUserAuthByUserId(userId);
        boolean verified = userAuth != null
                && Objects.equals(
                userAuth.getAuditStatus(),
                AuditStatus.APPROVED.getCode()
        );

        Set<String> roles = loadRoles(userId, verified, serviceToken);
        Set<String> authorities = RolePermissionMapping.permissionsFor(roles);

        return new AuthenticationSnapshot(
                user.getAccountStatus(),
                verified,
                roles,
                authorities,
                roles.contains(RoleConstants.SUPER_ADMIN)
        );
    }

    private Set<String> loadRoles(
            Long userId,
            boolean verified,
            boolean serviceToken
    ) {
        Set<String> roles = new HashSet<>();
        boolean botServiceIdentity = serviceToken
                && Objects.equals(
                quantabotProperties.getBotUserId(),
                userId
        );

        if (botServiceIdentity) {
            roles.add(RoleConstants.BOT);
        }
        roles.add(RoleConstants.USER);
        if (verified) {
            roles.add(RoleConstants.VERIFIED_USER);
        }

        List<String> assignedRoles =
                userRoleMapper.findRoleCodesByUserId(userId);
        if (assignedRoles == null) {
            return Set.copyOf(roles);
        }

        for (String assignedRole : assignedRoles) {
            if (RoleConstants.BOT.equals(assignedRole)
                    && !botServiceIdentity) {
                log.warn(
                        "忽略未通过 service 身份校验的 BOT 角色，userId={}",
                        userId
                );
                continue;
            }

            if (RolePermissionMapping.isManagementRole(assignedRole)) {
                roles.add(assignedRole);
            } else {
                log.warn(
                        "忽略未知用户角色，userId={}，role={}",
                        userId,
                        assignedRole
                );
            }
        }
        return Set.copyOf(roles);
    }

    private record AuthenticationCacheKey(
            Long userId,
            boolean serviceToken
    ) {
    }
}
