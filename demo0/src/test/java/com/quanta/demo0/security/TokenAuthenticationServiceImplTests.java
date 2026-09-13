package com.quanta.demo0.security;

import com.quanta.demo0.constant.JwtClaimsConstant;
import com.quanta.demo0.constant.RedisConstants;
import com.quanta.demo0.entity.User;
import com.quanta.demo0.entity.UserAuth;
import com.quanta.demo0.enums.AuditStatus;
import com.quanta.demo0.mapper.UserMapper;
import com.quanta.demo0.mapper.UserRoleMapper;
import com.quanta.demo0.properties.JwtProperties;
import com.quanta.demo0.utils.JwtUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * 统一 Token 认证服务单元测试。
 *
 * 测试只使用 Mockito，不连接真实 Redis 和 MySQL，
 * 用于锁定 JWT、登录态、封禁状态和认证缓存的判断顺序。
 */
class TokenAuthenticationServiceImplTests {

    private static final String SECRET_KEY =
            "01234567890123456789012345678901";
    private static final String OTHER_SECRET_KEY =
            "abcdefghijklmnopqrstuvwxyz123456";
    private static final Long USER_ID = 7L;

    private JwtProperties jwtProperties;
    private StringRedisTemplate stringRedisTemplate;
    private ValueOperations<String, String> valueOperations;
    private UserMapper userMapper;
    private UserRoleMapper userRoleMapper;
    private TokenAuthenticationServiceImpl authenticationService;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        jwtProperties = new JwtProperties();
        jwtProperties.setUserSecretKey(SECRET_KEY);
        jwtProperties.setUserTokenName("authorization");
        jwtProperties.setUserTtl(60_000L);

        stringRedisTemplate = mock(StringRedisTemplate.class);
        valueOperations = mock(ValueOperations.class);
        userMapper = mock(UserMapper.class);
        userRoleMapper = mock(UserRoleMapper.class);

        when(stringRedisTemplate.opsForValue())
                .thenReturn(valueOperations);
        when(userRoleMapper.findRoleCodesByUserId(USER_ID))
                .thenReturn(List.of());

        authenticationService = new TokenAuthenticationServiceImpl();
        ReflectionTestUtils.setField(
                authenticationService,
                "jwtProperties",
                jwtProperties
        );
        ReflectionTestUtils.setField(
                authenticationService,
                "stringRedisTemplate",
                stringRedisTemplate
        );
        ReflectionTestUtils.setField(
                authenticationService,
                "userMapper",
                userMapper
        );
        ReflectionTestUtils.setField(
                authenticationService,
                "userRoleMapper",
                userRoleMapper
        );
    }

    @Test
    void missingTokenReturnsTokenMissing() {
        TokenAuthenticationException exception = assertThrows(
                TokenAuthenticationException.class,
                () -> authenticationService.authenticate(" ")
        );

        assertEquals(
                TokenAuthenticationFailureReason.TOKEN_MISSING,
                exception.getReason()
        );
        verify(valueOperations, never()).get(anyString());
        verifyNoInteractions(userMapper);
    }

    @Test
    void forgedTokenReturnsTokenInvalid() {
        String forgedToken = createToken(
                OTHER_SECRET_KEY,
                60_000L
        );

        TokenAuthenticationException exception = authenticateFails(forgedToken);

        assertEquals(
                TokenAuthenticationFailureReason.TOKEN_INVALID,
                exception.getReason()
        );
        verify(valueOperations, never()).get(anyString());
        verifyNoInteractions(userMapper);
    }

    @Test
    void expiredTokenReturnsTokenExpired() {
        String expiredToken = createToken(SECRET_KEY, -1_000L);

        TokenAuthenticationException exception = authenticateFails(expiredToken);

        assertEquals(
                TokenAuthenticationFailureReason.TOKEN_EXPIRED,
                exception.getReason()
        );
        verify(valueOperations, never()).get(anyString());
        verifyNoInteractions(userMapper);
    }

    @Test
    void missingRedisSessionReturnsSessionNotFound() {
        String token = createToken(SECRET_KEY, 60_000L);
        when(valueOperations.get(loginKey())).thenReturn(null);

        TokenAuthenticationException exception = authenticateFails(token);

        assertEquals(
                TokenAuthenticationFailureReason.SESSION_NOT_FOUND,
                exception.getReason()
        );
        verifyNoInteractions(userMapper);
    }

    @Test
    void replacedRedisTokenReturnsSessionMismatch() {
        String token = createToken(SECRET_KEY, 60_000L);
        when(valueOperations.get(loginKey()))
                .thenReturn("new-login-token");

        TokenAuthenticationException exception = authenticateFails(token);

        assertEquals(
                TokenAuthenticationFailureReason.SESSION_MISMATCH,
                exception.getReason()
        );
        verifyNoInteractions(userMapper);
    }

    @Test
    void redisBannedMarkerReturnsUserBanned() {
        String token = createToken(SECRET_KEY, 60_000L);
        when(valueOperations.get(loginKey())).thenReturn(token);
        when(stringRedisTemplate.hasKey(bannedKey())).thenReturn(true);

        TokenAuthenticationException exception = authenticateFails(token);

        assertEquals(
                TokenAuthenticationFailureReason.USER_BANNED,
                exception.getReason()
        );
        verifyNoInteractions(userMapper);
    }

    @Test
    void missingDatabaseUserReturnsUserNotFound() {
        String token = createToken(SECRET_KEY, 60_000L);
        prepareCurrentSession(token);
        when(userMapper.getById(USER_ID)).thenReturn(null);

        TokenAuthenticationException exception = authenticateFails(token);

        assertEquals(
                TokenAuthenticationFailureReason.USER_NOT_FOUND,
                exception.getReason()
        );
    }

    @Test
    void databaseBannedStatusReturnsUserBanned() {
        String token = createToken(SECRET_KEY, 60_000L);
        prepareCurrentSession(token);
        when(userMapper.getById(USER_ID))
                .thenReturn(normalUser(0, 1));

        TokenAuthenticationException exception = authenticateFails(token);

        assertEquals(
                TokenAuthenticationFailureReason.USER_BANNED,
                exception.getReason()
        );
    }

    @Test
    void verifiedCacheHitDoesNotQueryAuthenticationTable() {
        String token = createToken(SECRET_KEY, 60_000L);
        prepareCurrentSession(token);
        when(userMapper.getById(USER_ID))
                .thenReturn(normalUser(0, 0));
        when(valueOperations.get(verifiedKey())).thenReturn("1");

        AuthenticatedUser authenticatedUser =
                authenticationService.authenticate(token);

        assertEquals(USER_ID, authenticatedUser.getUserId());
        assertTrue(authenticatedUser.getVerified());
        assertFalse(authenticatedUser.getAdmin());
        assertTrue(authenticatedUser.getRoles().contains("USER"));
        assertTrue(authenticatedUser.getRoles().contains("VERIFIED_USER"));
        assertTrue(authenticatedUser.getAuthorities().isEmpty());
        verify(userMapper, never()).getUserAuthByUserId(USER_ID);
    }

    @Test
    void verifiedCacheMissQueriesDatabaseAndWritesCache() {
        String token = createToken(SECRET_KEY, 60_000L);
        prepareCurrentSession(token);
        when(userMapper.getById(USER_ID))
                .thenReturn(normalUser(1, 0));
        when(valueOperations.get(verifiedKey())).thenReturn(null);
        when(userMapper.getUserAuthByUserId(USER_ID))
                .thenReturn(UserAuth.builder()
                        .userId(USER_ID)
                        .auditStatus(AuditStatus.APPROVED.getCode())
                        .build());
        when(userRoleMapper.findRoleCodesByUserId(USER_ID))
                .thenReturn(List.of("SUPER_ADMIN"));

        AuthenticatedUser authenticatedUser =
                authenticationService.authenticate(token);

        assertTrue(authenticatedUser.getVerified());
        assertTrue(authenticatedUser.getAdmin());
        assertTrue(authenticatedUser.getRoles().contains("SUPER_ADMIN"));
        verify(valueOperations).set(
                verifiedKey(),
                "1",
                RedisConstants.SECURITY_VERIFIED_TTL_MINUTES,
                TimeUnit.MINUTES
        );
    }

    @Test
    void unverifiedCacheMissWritesNegativeCache() {
        String token = createToken(SECRET_KEY, 60_000L);
        prepareCurrentSession(token);
        when(userMapper.getById(USER_ID))
                .thenReturn(normalUser(0, 0));
        when(valueOperations.get(verifiedKey())).thenReturn(null);
        when(userMapper.getUserAuthByUserId(USER_ID))
                .thenReturn(UserAuth.builder()
                        .userId(USER_ID)
                        .auditStatus(AuditStatus.PENDING.getCode())
                        .build());

        AuthenticatedUser authenticatedUser =
                authenticationService.authenticate(token);

        assertFalse(authenticatedUser.getVerified());
        assertEquals(1, authenticatedUser.getRoles().size());
        assertTrue(authenticatedUser.getRoles().contains("USER"));
        verify(valueOperations).set(
                verifiedKey(),
                "0",
                RedisConstants.SECURITY_VERIFIED_TTL_MINUTES,
                TimeUnit.MINUTES
        );
    }

    private void prepareCurrentSession(String token) {
        when(valueOperations.get(loginKey())).thenReturn(token);
        when(stringRedisTemplate.hasKey(bannedKey())).thenReturn(false);
    }

    private User normalUser(Integer isAdmin, Integer accountStatus) {
        return User.builder()
                .id(USER_ID)
                .isAdmin(isAdmin)
                .accountStatus(accountStatus)
                .build();
    }

    private TokenAuthenticationException authenticateFails(String token) {
        return assertThrows(
                TokenAuthenticationException.class,
                () -> authenticationService.authenticate(token)
        );
    }

    private String createToken(String secretKey, long ttlMillis) {
        return JwtUtil.createJWT(
                secretKey,
                ttlMillis,
                Map.of(JwtClaimsConstant.USER_ID, USER_ID)
        );
    }

    private String loginKey() {
        return RedisConstants.LOGIN_USER_KEY + USER_ID;
    }

    private String bannedKey() {
        return RedisConstants.USER_BANNED_KEY + USER_ID;
    }

    private String verifiedKey() {
        return RedisConstants.SECURITY_VERIFIED_KEY + USER_ID;
    }
}
