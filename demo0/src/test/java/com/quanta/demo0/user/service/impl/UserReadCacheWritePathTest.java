package com.quanta.demo0.user.service.impl;

import com.quanta.demo0.platform.security.service.impl.AdminRoleServiceImpl;


import com.quanta.demo0.platform.security.constant.RoleConstants;
import com.quanta.demo0.identity.dto.IdentityAuditDTO;
import com.quanta.demo0.identity.mapper.IdentityMapper;
import com.quanta.demo0.identity.service.IdentityQueryService;
import com.quanta.demo0.identity.service.impl.IdentityQueryServiceImpl;
import com.quanta.demo0.user.dto.UserInfoDTO;
import com.quanta.demo0.user.dto.UserLoginDTO;
import com.quanta.demo0.platform.security.context.BaseContext;
import com.quanta.demo0.user.entity.User;
import com.quanta.demo0.identity.entity.UserAuth;
import com.quanta.demo0.user.vo.UserAuthInfoVO;
import com.quanta.demo0.user.mapper.UserMapper;
import com.quanta.demo0.platform.security.mapper.UserRoleMapper;
import com.quanta.demo0.platform.security.properties.QuantabotProperties;
import com.quanta.demo0.platform.redis.properties.ReadPathCacheProperties;
import com.quanta.demo0.platform.security.model.AuthenticationSnapshot;
import com.quanta.demo0.platform.security.service.AuthenticationSnapshotCache;
import com.quanta.demo0.platform.security.service.impl.AuthenticationSnapshotCacheImpl;
import com.quanta.demo0.platform.audit.service.AdminAuditRecorder;
import com.quanta.demo0.identity.service.impl.IdentityExamServiceImpl;
import com.quanta.demo0.search.service.TrendingCacheInvalidator;
import com.quanta.demo0.user.service.AdminUserService;
import com.quanta.demo0.user.service.UserAccountService;
import com.quanta.demo0.user.service.AuthorProfileCache;
import com.quanta.demo0.user.service.UserQueryService;
import com.quanta.demo0.user.service.impl.AuthorProfileCacheImpl;
import com.quanta.demo0.notification.mq.producer.NotificationEventProducer;
import com.quanta.demo0.user.service.UserReadCacheInvalidator;
import com.quanta.demo0.user.service.impl.AdminUserServiceImpl;
import com.quanta.demo0.user.service.impl.UserAccountServiceImpl;
import com.quanta.demo0.user.service.impl.UserProfileServiceImpl;
import com.quanta.demo0.user.service.impl.UserQueryServiceImpl;
import com.quanta.demo0.user.service.impl.UserReadCacheInvalidatorImpl;
import com.quanta.demo0.user.properties.WeChatProperties;
import com.quanta.demo0.content.service.ContentQueryService;
import com.quanta.demo0.follow.service.FollowQueryService;
import com.quanta.demo0.moderation.utils.SensitiveWordChecker;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Answers;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static com.quanta.demo0.platform.redis.constant.RedisConstants.SECURITY_VERIFIED_KEY;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 用户资料、身份、角色和账号状态写路径的读缓存失效测试。
 *
 * 业务入口使用真实 Service，UserReadCacheInvalidator 使用生产实现，
 * 只替换数据库、Outbox 和 Redis 外部依赖；断言实际缓存依赖在提交/回滚后的变化。
 */
class UserReadCacheWritePathTest {

    private static final Long USER_ID = 19L;

    private UserMapper userMapper;
    private IdentityMapper identityMapper;
    private UserQueryService userQueryService;
    private IdentityQueryService identityQueryService;
    private UserAccountService userAccountService;
    private UserRoleMapper userRoleMapper;
    private AuthenticationSnapshotCache authenticationCache;
    private AuthorProfileCache authorProfileCache;
    private StringRedisTemplate redisTemplate;
    private UserReadCacheInvalidator invalidator;

    @BeforeEach
    void setUp() {
        userMapper = mock(UserMapper.class);
        identityMapper = mock(IdentityMapper.class);
        userQueryService = new UserQueryServiceImpl(userMapper);
        identityQueryService = new IdentityQueryServiceImpl(identityMapper);
        userRoleMapper = mock(UserRoleMapper.class);
        ReadPathCacheProperties cacheProperties = new ReadPathCacheProperties();
        authenticationCache = new AuthenticationSnapshotCacheImpl(
                userQueryService,
                identityQueryService,
                userRoleMapper,
                new QuantabotProperties(),
                cacheProperties
        );
        authorProfileCache = new AuthorProfileCacheImpl(userMapper, cacheProperties);
        redisTemplate = mock(StringRedisTemplate.class, Answers.RETURNS_DEEP_STUBS);
        invalidator = new UserReadCacheInvalidatorImpl(
                authenticationCache,
                authorProfileCache,
                redisTemplate
        );
        userAccountService = userAccountService();
        BaseContext.setCurrentId(USER_ID);
    }

    @AfterEach
    void tearDown() {
        BaseContext.removeCurrentId();
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
        TransactionSynchronizationManager.setActualTransactionActive(false);
    }

    @Test
    void successfulProfileUpdateEvictsAuthorCache() {
        UserProfileServiceImpl service = userProfileService();
        UserAuthInfoVO oldProfile = profile("旧昵称");
        UserAuthInfoVO newProfile = profile("新昵称");
        when(userMapper.selectUserAuthInfoById(USER_ID)).thenReturn(oldProfile, newProfile);
        assertEquals(oldProfile, authorProfileCache.get(USER_ID));
        when(userMapper.updateById(any(User.class))).thenReturn(1);

        service.updateUserInfo(UserInfoDTO.builder().nickName("新昵称").build());

        assertEquals(newProfile, authorProfileCache.get(USER_ID));
    }

    @Test
    void failedProfileUpdateDoesNotEvictAuthorCache() {
        UserProfileServiceImpl service = userProfileService();
        UserAuthInfoVO oldProfile = profile("旧昵称");
        UserAuthInfoVO newProfile = profile("不应提前出现");
        when(userMapper.selectUserAuthInfoById(USER_ID)).thenReturn(oldProfile, newProfile);
        assertEquals(oldProfile, authorProfileCache.get(USER_ID));
        when(userMapper.updateById(any(User.class))).thenReturn(0);

        assertThrows(RuntimeException.class,
                () -> service.updateUserInfo(UserInfoDTO.builder().nickName("新昵称").build()));

        assertEquals(oldProfile, authorProfileCache.get(USER_ID));
    }

    @Test
    void changedWechatProfileEvictsAuthorCache() {
        UserAccountServiceImpl service = userAccountService();
        UserAuthInfoVO oldProfile = profile("用户_默认");
        UserAuthInfoVO newProfile = profile("微信昵称");
        when(userMapper.selectUserAuthInfoById(USER_ID)).thenReturn(oldProfile, newProfile);
        assertEquals(oldProfile, authorProfileCache.get(USER_ID));
        User existing = User.builder()
                .id(USER_ID)
                .openid("test_openid_123456")
                .nickName("用户_默认")
                .avatarUrl(null)
                .build();
        when(userMapper.getByOpenid("test_openid_123456")).thenReturn(existing);
        when(userMapper.updateById(any(User.class))).thenReturn(1);

        service.weChatLogin(UserLoginDTO.builder()
                .code("test")
                .nickName("微信昵称")
                .avatarUrl("https://avatar.example/avatar.png")
                .build());

        verify(userMapper).updateById(any(User.class));
        assertEquals(newProfile, authorProfileCache.get(USER_ID));
    }

    @Test
    void unchangedWechatProfileDoesNotEvictAuthorCache() {
        UserAccountServiceImpl service = userAccountService();
        UserAuthInfoVO oldProfile = profile("用户自定义");
        UserAuthInfoVO newProfile = profile("不应提前出现");
        when(userMapper.selectUserAuthInfoById(USER_ID)).thenReturn(oldProfile, newProfile);
        assertEquals(oldProfile, authorProfileCache.get(USER_ID));
        User existing = User.builder()
                .id(USER_ID)
                .openid("test_openid_123456")
                .nickName("用户自定义")
                .avatarUrl("https://avatar.example/existing.png")
                .build();
        when(userMapper.getByOpenid("test_openid_123456")).thenReturn(existing);

        service.weChatLogin(UserLoginDTO.builder()
                .code("test")
                .nickName("微信昵称")
                .avatarUrl("https://avatar.example/avatar.png")
                .build());

        verify(userMapper, never()).updateById(any(User.class));
        assertEquals(oldProfile, authorProfileCache.get(USER_ID));
    }

    @Test
    void identityRejectionEvictsAllUserCachesAfterCommit() {
        IdentityExamServiceImpl service = identityService();
        UserAuth auth = UserAuth.builder().authId(88L).userId(USER_ID).build();
        User oldUser = User.builder().id(USER_ID).accountStatus(0).build();
        User newUser = User.builder().id(USER_ID).accountStatus(0).build();
        UserAuthInfoVO oldProfile = profile("认证前");
        UserAuthInfoVO newProfile = profile("认证后");
        when(userMapper.getById(USER_ID)).thenReturn(oldUser, newUser);
        when(identityMapper.getUserAuthByUserId(USER_ID)).thenReturn(
                UserAuth.builder().userId(USER_ID).auditStatus(1).build(),
                UserAuth.builder().userId(USER_ID).auditStatus(2).build());
        when(userRoleMapper.findRoleCodesByUserId(USER_ID)).thenReturn(List.of(), List.of());
        when(userMapper.selectUserAuthInfoById(USER_ID)).thenReturn(oldProfile, newProfile);
        assertEquals(oldProfile, authorProfileCache.get(USER_ID));
        AuthenticationSnapshot oldSnapshot = authenticationCache.get(USER_ID, false);
        assertEquals(true, oldSnapshot.verified());
        when(identityMapper.getUserAuthByAuthId(88L)).thenReturn(auth);
        when(userMapper.updateById(any(User.class))).thenReturn(1);
        beginTransaction();

        service.audit(IdentityAuditDTO.builder()
                .authId(88L)
                .auditResult(2)
                .build());

        assertEquals(oldProfile, authorProfileCache.get(USER_ID));
        assertEquals(oldSnapshot, authenticationCache.get(USER_ID, false));
        commit();

        assertEquals(newProfile, authorProfileCache.get(USER_ID));
        assertEquals(false, authenticationCache.get(USER_ID, false).verified());
        verify(redisTemplate).delete(SECURITY_VERIFIED_KEY + USER_ID);
    }

    @Test
    void identityApprovalEvictsAllUserCachesAfterCommit() {
        IdentityExamServiceImpl service = identityService();
        UserAuth auth = UserAuth.builder().authId(88L).userId(USER_ID).build();
        User oldUser = User.builder().id(USER_ID).accountStatus(0).build();
        User newUser = User.builder().id(USER_ID).accountStatus(0).build();
        UserAuthInfoVO oldProfile = profile("认证前");
        UserAuthInfoVO newProfile = profile("认证后");
        when(userMapper.getById(USER_ID)).thenReturn(oldUser, newUser);
        when(identityMapper.getUserAuthByUserId(USER_ID)).thenReturn(
                UserAuth.builder().userId(USER_ID).auditStatus(2).build(),
                UserAuth.builder().userId(USER_ID).auditStatus(1).build());
        when(userRoleMapper.findRoleCodesByUserId(USER_ID)).thenReturn(List.of(), List.of());
        when(userMapper.selectUserAuthInfoById(USER_ID)).thenReturn(oldProfile, newProfile);
        assertEquals(oldProfile, authorProfileCache.get(USER_ID));
        AuthenticationSnapshot oldSnapshot = authenticationCache.get(USER_ID, false);
        assertEquals(false, oldSnapshot.verified());
        when(identityMapper.getUserAuthByAuthId(88L)).thenReturn(auth);
        when(userMapper.updateById(any(User.class))).thenReturn(1);
        beginTransaction();

        service.audit(IdentityAuditDTO.builder()
                .authId(88L)
                .auditResult(1)
                .build());

        assertEquals(oldProfile, authorProfileCache.get(USER_ID));
        assertEquals(oldSnapshot, authenticationCache.get(USER_ID, false));
        commit();

        assertEquals(newProfile, authorProfileCache.get(USER_ID));
        assertEquals(true, authenticationCache.get(USER_ID, false).verified());
        verify(redisTemplate).delete(SECURITY_VERIFIED_KEY + USER_ID);
    }

    @Test
    void identityRejectionRollbackKeepsAllUserCaches() {
        IdentityExamServiceImpl service = identityService();
        UserAuthInfoVO oldProfile = profile("认证前");
        when(userMapper.selectUserAuthInfoById(USER_ID)).thenReturn(oldProfile);
        assertEquals(oldProfile, authorProfileCache.get(USER_ID));
        UserAuth oldAuth = UserAuth.builder().userId(USER_ID).auditStatus(1).build();
        when(userMapper.getById(USER_ID)).thenReturn(
                User.builder().id(USER_ID).accountStatus(0).build(),
                User.builder().id(USER_ID).accountStatus(0).build());
        when(identityMapper.getUserAuthByUserId(USER_ID)).thenReturn(oldAuth);
        when(userRoleMapper.findRoleCodesByUserId(USER_ID)).thenReturn(List.of());
        AuthenticationSnapshot oldSnapshot = authenticationCache.get(USER_ID, false);
        when(identityMapper.getUserAuthByAuthId(88L)).thenReturn(
                UserAuth.builder().authId(88L).userId(USER_ID).build());
        when(userMapper.updateById(any(User.class))).thenReturn(1);
        beginTransaction();

        service.audit(IdentityAuditDTO.builder().authId(88L).auditResult(2).build());
        rollback();

        assertEquals(oldProfile, authorProfileCache.get(USER_ID));
        assertEquals(oldSnapshot, authenticationCache.get(USER_ID, false));
        verify(redisTemplate, never()).delete(SECURITY_VERIFIED_KEY + USER_ID);
    }

    @Test
    void identityRejectionFailureDoesNotRegisterInvalidation() {
        IdentityExamServiceImpl service = identityService();
        UserAuthInfoVO oldProfile = profile("认证前");
        when(userMapper.selectUserAuthInfoById(USER_ID)).thenReturn(oldProfile);
        assertEquals(oldProfile, authorProfileCache.get(USER_ID));
        UserAuth oldAuth = UserAuth.builder().userId(USER_ID).auditStatus(1).build();
        when(userMapper.getById(USER_ID)).thenReturn(
                User.builder().id(USER_ID).accountStatus(0).build(),
                User.builder().id(USER_ID).accountStatus(0).build());
        when(identityMapper.getUserAuthByUserId(USER_ID)).thenReturn(oldAuth);
        when(userRoleMapper.findRoleCodesByUserId(USER_ID)).thenReturn(List.of());
        AuthenticationSnapshot oldSnapshot = authenticationCache.get(USER_ID, false);
        when(identityMapper.getUserAuthByAuthId(88L)).thenReturn(
                UserAuth.builder().authId(88L).userId(USER_ID).build());
        doThrow(new IllegalStateException("database unavailable"))
                .when(identityMapper).updateUserAuth(any(UserAuth.class));

        assertThrows(IllegalStateException.class,
                () -> service.audit(IdentityAuditDTO.builder().authId(88L).auditResult(2).build()));

        assertEquals(oldProfile, authorProfileCache.get(USER_ID));
        assertEquals(oldSnapshot, authenticationCache.get(USER_ID, false));
        verify(redisTemplate, never()).delete(SECURITY_VERIFIED_KEY + USER_ID);
    }

    @Test
    void grantRoleEvictsAuthenticationCacheAfterCommit() {
        AdminRoleServiceImpl service = roleService();
        UserRoleMapper roleMapper = (UserRoleMapper) ReflectionTestUtils.getField(service, "userRoleMapper");
        UserAuth oldAuth = UserAuth.builder().userId(USER_ID).auditStatus(1).build();
        when(userMapper.getById(USER_ID)).thenReturn(
                User.builder().id(USER_ID).accountStatus(0).build(),
                User.builder().id(USER_ID).accountStatus(0).build(),
                User.builder().id(USER_ID).accountStatus(0).build());
        when(identityMapper.getUserAuthByUserId(USER_ID)).thenReturn(oldAuth, oldAuth, oldAuth);
        AtomicInteger roleReads = new AtomicInteger();
        when(roleMapper.findRoleCodesByUserId(USER_ID)).thenAnswer(invocation ->
                roleReads.getAndIncrement() < 2
                        ? List.of()
                        : List.of(RoleConstants.CONTENT_AUDITOR));
        AuthenticationSnapshot oldSnapshot = authenticationCache.get(USER_ID, false);
        when(roleMapper.grantRole(USER_ID, RoleConstants.CONTENT_AUDITOR, null)).thenReturn(1);
        beginTransaction();

        service.grantRole(USER_ID, RoleConstants.CONTENT_AUDITOR);
        assertEquals(oldSnapshot, authenticationCache.get(USER_ID, false));
        commit();

        AuthenticationSnapshot refreshed = authenticationCache.get(USER_ID, false);
        assertEquals(true, refreshed.roles().contains(RoleConstants.CONTENT_AUDITOR));
    }

    @Test
    void revokeRoleEvictsAuthenticationCacheAfterCommit() {
        AdminRoleServiceImpl service = roleService();
        UserRoleMapper roleMapper = (UserRoleMapper) ReflectionTestUtils.getField(service, "userRoleMapper");
        UserAuth oldAuth = UserAuth.builder().userId(USER_ID).auditStatus(1).build();
        when(userMapper.getById(USER_ID)).thenReturn(
                User.builder().id(USER_ID).accountStatus(0).build(),
                User.builder().id(USER_ID).accountStatus(0).build(),
                User.builder().id(USER_ID).accountStatus(0).build());
        when(identityMapper.getUserAuthByUserId(USER_ID)).thenReturn(oldAuth, oldAuth, oldAuth);
        AtomicInteger roleReads = new AtomicInteger();
        when(roleMapper.findRoleCodesByUserId(USER_ID)).thenAnswer(invocation ->
                roleReads.getAndIncrement() < 2
                        ? List.of(RoleConstants.CONTENT_AUDITOR)
                        : List.of());
        AuthenticationSnapshot oldSnapshot = authenticationCache.get(USER_ID, false);
        when(roleMapper.revokeRole(USER_ID, RoleConstants.CONTENT_AUDITOR)).thenReturn(1);
        beginTransaction();

        service.revokeRole(USER_ID, RoleConstants.CONTENT_AUDITOR);
        assertEquals(oldSnapshot, authenticationCache.get(USER_ID, false));
        commit();

        AuthenticationSnapshot refreshed = authenticationCache.get(USER_ID, false);
        assertEquals(false, refreshed.roles().contains(RoleConstants.CONTENT_AUDITOR));
    }

    @Test
    void revokeRoleRollbackKeepsAuthenticationCache() {
        AdminRoleServiceImpl service = roleService();
        UserRoleMapper roleMapper = (UserRoleMapper) ReflectionTestUtils.getField(service, "userRoleMapper");
        UserAuth oldAuth = UserAuth.builder().userId(USER_ID).auditStatus(1).build();
        when(userMapper.getById(USER_ID)).thenReturn(
                User.builder().id(USER_ID).accountStatus(0).build(),
                User.builder().id(USER_ID).accountStatus(0).build());
        when(identityMapper.getUserAuthByUserId(USER_ID)).thenReturn(oldAuth, oldAuth);
        when(roleMapper.findRoleCodesByUserId(USER_ID)).thenReturn(
                List.of(RoleConstants.CONTENT_AUDITOR),
                List.of(RoleConstants.CONTENT_AUDITOR),
                List.of(RoleConstants.CONTENT_AUDITOR),
                List.of());
        AuthenticationSnapshot oldSnapshot = authenticationCache.get(USER_ID, false);
        when(roleMapper.revokeRole(USER_ID, RoleConstants.CONTENT_AUDITOR)).thenReturn(1);
        beginTransaction();

        service.revokeRole(USER_ID, RoleConstants.CONTENT_AUDITOR);
        rollback();

        assertEquals(oldSnapshot, authenticationCache.get(USER_ID, false));
    }

    @Test
    void roleWriteFailureDoesNotEvictAuthenticationCache() {
        AdminRoleServiceImpl service = roleService();
        UserRoleMapper roleMapper = (UserRoleMapper) ReflectionTestUtils.getField(service, "userRoleMapper");
        UserAuth oldAuth = UserAuth.builder().userId(USER_ID).auditStatus(1).build();
        when(userMapper.getById(USER_ID)).thenReturn(
                User.builder().id(USER_ID).accountStatus(0).build(),
                User.builder().id(USER_ID).accountStatus(0).build());
        when(identityMapper.getUserAuthByUserId(USER_ID)).thenReturn(oldAuth);
        when(roleMapper.findRoleCodesByUserId(USER_ID)).thenReturn(List.of(), List.of());
        AuthenticationSnapshot oldSnapshot = authenticationCache.get(USER_ID, false);
        doThrow(new IllegalStateException("database unavailable"))
                .when(roleMapper).grantRole(USER_ID, RoleConstants.CONTENT_AUDITOR, null);

        assertThrows(IllegalStateException.class,
                () -> service.grantRole(USER_ID, RoleConstants.CONTENT_AUDITOR));

        assertEquals(oldSnapshot, authenticationCache.get(USER_ID, false));
    }

    @Test
    void banUserEvictsAuthenticationAndAuthorCachesAfterCommit() {
        AdminUserServiceImpl service = adminUserService();
        User oldUser = User.builder().id(USER_ID).accountStatus(0).build();
        User newUser = User.builder().id(USER_ID).accountStatus(1).build();
        when(userMapper.getById(USER_ID)).thenReturn(oldUser, oldUser, newUser);
        when(identityMapper.getUserAuthByUserId(USER_ID)).thenReturn(
                UserAuth.builder().userId(USER_ID).auditStatus(1).build(),
                UserAuth.builder().userId(USER_ID).auditStatus(1).build(),
                UserAuth.builder().userId(USER_ID).auditStatus(1).build());
        when(userRoleMapper.findRoleCodesByUserId(USER_ID)).thenReturn(List.of(), List.of(), List.of());
        UserAuthInfoVO oldProfile = profile("封禁前");
        UserAuthInfoVO newProfile = profile("封禁后");
        when(userMapper.selectUserAuthInfoById(USER_ID)).thenReturn(oldProfile, newProfile);
        assertEquals(oldProfile, authorProfileCache.get(USER_ID));
        assertEquals(0, authenticationCache.get(USER_ID, false).accountStatus());
        when(userMapper.updateById(any(User.class))).thenReturn(1);
        beginTransaction();

        service.banUser(USER_ID);
        assertEquals(oldProfile, authorProfileCache.get(USER_ID));
        commit();

        assertEquals(newProfile, authorProfileCache.get(USER_ID));
        assertEquals(1, authenticationCache.get(USER_ID, false).accountStatus());
        verify(redisTemplate).delete(SECURITY_VERIFIED_KEY + USER_ID);
    }

    @Test
    void unbanUserEvictsAuthenticationAndAuthorCachesAfterCommit() {
        AdminUserServiceImpl service = adminUserService();
        User oldUser = User.builder().id(USER_ID).accountStatus(1).build();
        User newUser = User.builder().id(USER_ID).accountStatus(0).build();
        when(userMapper.getById(USER_ID)).thenReturn(oldUser, oldUser, newUser);
        when(identityMapper.getUserAuthByUserId(USER_ID)).thenReturn(
                UserAuth.builder().userId(USER_ID).auditStatus(1).build(),
                UserAuth.builder().userId(USER_ID).auditStatus(1).build(),
                UserAuth.builder().userId(USER_ID).auditStatus(1).build());
        when(userRoleMapper.findRoleCodesByUserId(USER_ID)).thenReturn(List.of(), List.of(), List.of());
        UserAuthInfoVO oldProfile = profile("解封前");
        UserAuthInfoVO newProfile = profile("解封后");
        when(userMapper.selectUserAuthInfoById(USER_ID)).thenReturn(oldProfile, newProfile);
        assertEquals(oldProfile, authorProfileCache.get(USER_ID));
        assertEquals(1, authenticationCache.get(USER_ID, false).accountStatus());
        when(userMapper.updateById(any(User.class))).thenReturn(1);
        beginTransaction();

        service.unbanUser(USER_ID);
        assertEquals(oldProfile, authorProfileCache.get(USER_ID));
        commit();

        assertEquals(newProfile, authorProfileCache.get(USER_ID));
        assertEquals(0, authenticationCache.get(USER_ID, false).accountStatus());
        verify(redisTemplate).delete(SECURITY_VERIFIED_KEY + USER_ID);
    }

    @Test
    void banUserFailureDoesNotEvictAnyUserCache() {
        AdminUserServiceImpl service = adminUserService();
        User oldUser = User.builder().id(USER_ID).accountStatus(0).build();
        when(userMapper.getById(USER_ID)).thenReturn(oldUser, oldUser);
        when(identityMapper.getUserAuthByUserId(USER_ID)).thenReturn(
                UserAuth.builder().userId(USER_ID).auditStatus(1).build());
        when(userRoleMapper.findRoleCodesByUserId(USER_ID)).thenReturn(List.of());
        UserAuthInfoVO oldProfile = profile("封禁前");
        when(userMapper.selectUserAuthInfoById(USER_ID)).thenReturn(oldProfile);
        assertEquals(oldProfile, authorProfileCache.get(USER_ID));
        AuthenticationSnapshot oldSnapshot = authenticationCache.get(USER_ID, false);
        doThrow(new IllegalStateException("database unavailable"))
                .when(userMapper).updateById(any(User.class));

        assertThrows(IllegalStateException.class, () -> service.banUser(USER_ID));

        assertEquals(oldProfile, authorProfileCache.get(USER_ID));
        assertEquals(oldSnapshot, authenticationCache.get(USER_ID, false));
    }

    @Test
    void banUserRollbackKeepsAllUserCaches() {
        AdminUserServiceImpl service = adminUserService();
        User oldUser = User.builder().id(USER_ID).accountStatus(0).build();
        when(userMapper.getById(USER_ID)).thenReturn(oldUser, oldUser);
        UserAuth oldAuth = UserAuth.builder().userId(USER_ID).auditStatus(1).build();
        when(identityMapper.getUserAuthByUserId(USER_ID)).thenReturn(oldAuth, oldAuth);
        when(userRoleMapper.findRoleCodesByUserId(USER_ID)).thenReturn(List.of(), List.of());
        UserAuthInfoVO oldProfile = profile("封禁前");
        when(userMapper.selectUserAuthInfoById(USER_ID)).thenReturn(oldProfile);
        assertEquals(oldProfile, authorProfileCache.get(USER_ID));
        AuthenticationSnapshot oldSnapshot = authenticationCache.get(USER_ID, false);
        when(userMapper.updateById(any(User.class))).thenReturn(1);
        beginTransaction();

        service.banUser(USER_ID);
        assertEquals(oldProfile, authorProfileCache.get(USER_ID));
        assertEquals(oldSnapshot, authenticationCache.get(USER_ID, false));
        rollback();

        assertEquals(oldProfile, authorProfileCache.get(USER_ID));
        assertEquals(oldSnapshot, authenticationCache.get(USER_ID, false));
        verify(redisTemplate, never()).delete(SECURITY_VERIFIED_KEY + USER_ID);
    }

    private UserProfileServiceImpl userProfileService() {
        SensitiveWordChecker sensitiveWordChecker = mock(SensitiveWordChecker.class);
        when(sensitiveWordChecker.replaceSensitiveWords(anyString()))
                .thenAnswer(invocation -> invocation.getArgument(0));
        return new UserProfileServiceImpl(
                userMapper,
                userQueryService,
                sensitiveWordChecker,
                invalidator,
                identityQueryService,
                mock(FollowQueryService.class),
                mock(ContentQueryService.class)
        );
    }

    private UserAccountServiceImpl userAccountService() {
        SensitiveWordChecker sensitiveWordChecker = mock(SensitiveWordChecker.class);
        when(sensitiveWordChecker.replaceSensitiveWords(anyString()))
                .thenAnswer(invocation -> invocation.getArgument(0));
        return new UserAccountServiceImpl(
                mock(WeChatProperties.class),
                userMapper,
                sensitiveWordChecker,
                invalidator
        );
    }

    private IdentityExamServiceImpl identityService() {
        IdentityExamServiceImpl service = new IdentityExamServiceImpl();
        ReflectionTestUtils.setField(service, "identityMapper", identityMapper);
        ReflectionTestUtils.setField(service, "userQueryService", userQueryService);
        ReflectionTestUtils.setField(service, "userAccountService", userAccountService);
        ReflectionTestUtils.setField(service, "notificationEventProducer", mock(NotificationEventProducer.class));
        ReflectionTestUtils.setField(service, "userReadCacheInvalidator", invalidator);
        ReflectionTestUtils.setField(service, "adminAuditRecorder", mock(AdminAuditRecorder.class));
        return service;
    }

    private AdminRoleServiceImpl roleService() {
        AdminRoleServiceImpl service = new AdminRoleServiceImpl();
        ReflectionTestUtils.setField(service, "userQueryService", userQueryService);
        ReflectionTestUtils.setField(service, "userRoleMapper", userRoleMapper);
        ReflectionTestUtils.setField(service, "adminAuditRecorder", mock(AdminAuditRecorder.class));
        ReflectionTestUtils.setField(service, "stringRedisTemplate", redisTemplate);
        ReflectionTestUtils.setField(service, "userReadCacheInvalidator", invalidator);
        return service;
    }

    private AdminUserServiceImpl adminUserService() {
        AdminUserServiceImpl service = new AdminUserServiceImpl();
        ReflectionTestUtils.setField(service, "userMapper", userMapper);
        ReflectionTestUtils.setField(service, "stringRedisTemplate", redisTemplate);
        ReflectionTestUtils.setField(service, "adminAuditRecorder", mock(AdminAuditRecorder.class));
        ReflectionTestUtils.setField(service, "userReadCacheInvalidator", invalidator);
        ReflectionTestUtils.setField(service, "trendingCacheInvalidator", mock(TrendingCacheInvalidator.class));
        return service;
    }

    private UserAuthInfoVO profile(String nickName) {
        return UserAuthInfoVO.builder()
                .userId(USER_ID)
                .nickName(nickName)
                .avatarUrl("https://avatar.example/" + nickName)
                .quantaDepartment("计算机")
                .quantaBatch("2023")
                .authStatus(1)
                .accountStatus(0)
                .build();
    }

    private void beginTransaction() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.initSynchronization();
    }

    private void commit() {
        for (TransactionSynchronization synchronization
                : TransactionSynchronizationManager.getSynchronizations()) {
            synchronization.afterCommit();
        }
    }

    private void rollback() {
        for (TransactionSynchronization synchronization
                : TransactionSynchronizationManager.getSynchronizations()) {
            synchronization.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);
        }
    }
}
