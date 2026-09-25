package com.quanta.demo0.security;

import com.quanta.demo0.constant.JwtClaimsConstant;
import com.quanta.demo0.constant.RedisConstants;
import com.quanta.demo0.properties.JwtProperties;
import com.quanta.demo0.utils.JwtUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 统一 Token 认证服务单元测试。
 *
 * <p>测试 JWT、Redis 当前会话与封禁标记的逐次校验，同时锁定认证服务
 * 只消费安全快照，不再自行读取 User、UserAuth、UserRole。</p>
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
    private AuthenticationSnapshotCache snapshotCache;
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
        snapshotCache = mock(AuthenticationSnapshotCache.class);

        when(stringRedisTemplate.opsForValue())
                .thenReturn(valueOperations);

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
                "authenticationSnapshotCache",
                snapshotCache
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
        verify(snapshotCache, never()).get(USER_ID, false);
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
        verify(snapshotCache, never()).get(USER_ID, false);
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
        verify(snapshotCache, never()).get(USER_ID, false);
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
        verify(snapshotCache, never()).get(USER_ID, false);
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
        verify(snapshotCache, never()).get(USER_ID, false);
    }

    @Test
    void redisBannedMarkerReturnsUserBanned() {
        String token = createToken(SECRET_KEY, 60_000L);
        prepareCurrentSession(token);
        when(stringRedisTemplate.hasKey(bannedKey())).thenReturn(true);

        TokenAuthenticationException exception = authenticateFails(token);

        assertEquals(
                TokenAuthenticationFailureReason.USER_BANNED,
                exception.getReason()
        );
        verify(snapshotCache, never()).get(USER_ID, false);
    }

    @Test
    void missingDatabaseUserReturnsUserNotFound() {
        String token = createToken(SECRET_KEY, 60_000L);
        prepareCurrentSession(token);
        when(snapshotCache.get(USER_ID, false)).thenReturn(null);

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
        when(snapshotCache.get(USER_ID, false))
                .thenReturn(snapshot(1, false, Set.of("USER"), Set.of(), false));

        TokenAuthenticationException exception = authenticateFails(token);

        assertEquals(
                TokenAuthenticationFailureReason.USER_BANNED,
                exception.getReason()
        );
    }

    @Test
    void snapshotHitStillChecksSessionAndBannedMarkerOnEveryRequest() {
        String token = createToken(SECRET_KEY, 60_000L);
        prepareCurrentSession(token);
        when(snapshotCache.get(USER_ID, false))
                .thenReturn(snapshot(0, true,
                        Set.of("USER", "VERIFIED_USER"),
                        Set.of(), false));

        AuthenticatedUser first = authenticationService.authenticate(token);
        AuthenticatedUser second = authenticationService.authenticate(token);

        assertEquals(USER_ID, first.getUserId());
        assertTrue(first.getVerified());
        assertNotSame(first, second);
        verify(valueOperations, org.mockito.Mockito.times(2))
                .get(loginKey());
        verify(stringRedisTemplate, org.mockito.Mockito.times(2))
                .hasKey(bannedKey());
        verify(snapshotCache, org.mockito.Mockito.times(2))
                .get(USER_ID, false);
    }

    @Test
    void snapshotAuthDataIsCopiedIntoNewAuthenticatedUserWithoutRedisVerifiedCache() {
        String token = createToken(SECRET_KEY, 60_000L);
        prepareCurrentSession(token);
        when(snapshotCache.get(USER_ID, false))
                .thenReturn(snapshot(0, true,
                        Set.of("USER", "SUPER_ADMIN"),
                        Set.of("ROLE_MANAGE"),
                        true));

        AuthenticatedUser authenticatedUser =
                authenticationService.authenticate(token);

        assertEquals(USER_ID, authenticatedUser.getUserId());
        assertTrue(authenticatedUser.getVerified());
        assertTrue(authenticatedUser.getAdmin());
        assertEquals(Set.of("USER", "SUPER_ADMIN"),
                authenticatedUser.getRoles());
        assertEquals(Set.of("ROLE_MANAGE"),
                authenticatedUser.getAuthorities());
        verify(valueOperations, never()).get(verifiedKey());
        verify(valueOperations, never()).set(
                org.mockito.ArgumentMatchers.eq(verifiedKey()),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.any()
        );
    }

    private AuthenticationSnapshot snapshot(
            Integer accountStatus,
            boolean verified,
            Set<String> roles,
            Set<String> authorities,
            boolean admin
    ) {
        return new AuthenticationSnapshot(
                accountStatus,
                verified,
                roles,
                authorities,
                admin
        );
    }

    private void prepareCurrentSession(String token) {
        when(valueOperations.get(loginKey())).thenReturn(token);
        when(stringRedisTemplate.hasKey(bannedKey())).thenReturn(false);
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
