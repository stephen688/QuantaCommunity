package com.quanta.demo0.platform.security.service.impl;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.quanta.demo0.platform.security.constant.RoleConstants;
import com.quanta.demo0.platform.security.constant.RolePermissionMapping;
import com.quanta.demo0.identity.service.IdentityQueryService;
import com.quanta.demo0.identity.vo.UserAuthStatusVO;
import com.quanta.demo0.platform.common.enums.AuditStatus;
import com.quanta.demo0.platform.security.mapper.UserRoleMapper;
import com.quanta.demo0.platform.security.model.AuthenticationSnapshot;
import com.quanta.demo0.platform.security.properties.QuantabotProperties;
import com.quanta.demo0.platform.security.service.AuthenticationSnapshotCache;
import com.quanta.demo0.platform.redis.properties.ReadPathCacheProperties;
import com.quanta.demo0.user.service.UserQueryService;
import com.quanta.demo0.user.vo.UserAccountVO;
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

    private final UserQueryService userQueryService;
    private final IdentityQueryService identityQueryService;
    private final UserRoleMapper userRoleMapper;
    private final QuantabotProperties quantabotProperties;
    private final Cache<AuthenticationCacheKey, AuthenticationSnapshot> cache;

    public AuthenticationSnapshotCacheImpl(
            UserQueryService userQueryService,
            IdentityQueryService identityQueryService,
            UserRoleMapper userRoleMapper,
            QuantabotProperties quantabotProperties,
            ReadPathCacheProperties cacheProperties
    ) {
        this.userQueryService = userQueryService;
        this.identityQueryService = identityQueryService;
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


    /**
     * 加载用户认证快照。
     * 包括用户角色、权限、账号状态、是否认证、是否为超级管理员。
     */
    private AuthenticationSnapshot load(Long userId, boolean serviceToken) {
        UserAccountVO user = userQueryService.getAccount(userId);
        if (user == null) {
            return null;
        }


        //1. 查询用户认证状态，判断是否认证
        UserAuthStatusVO userAuth = identityQueryService.getAuthStatus(userId);
        boolean verified = userAuth != null
                && Objects.equals(
                userAuth.getAuditStatus(),
                AuditStatus.APPROVED.getCode()
        );

        //2. 加载用户角色
               Set<String> roles = loadRoles(userId, verified, serviceToken);
                //3. 加载用户权限
        Set<String> authorities = RolePermissionMapping.permissionsFor(roles);

        return new AuthenticationSnapshot(
                user.getAccountStatus(),//账号状态
                verified,//是否认证
                roles,//角色
                authorities,//权限
                roles.contains(RoleConstants.SUPER_ADMIN)
        );
    }

    /**
     * 加载用户角色。
     * 包括默认角色USER、VERIFIED_USER、BOT、SUPER_ADMIN。
     */
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

        //3. 加载用户管理角色
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
