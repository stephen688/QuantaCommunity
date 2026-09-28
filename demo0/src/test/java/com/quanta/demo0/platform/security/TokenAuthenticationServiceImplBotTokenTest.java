package com.quanta.demo0.platform.security;

import com.quanta.demo0.platform.security.constant.JwtClaimsConstant;
import com.quanta.demo0.platform.redis.constant.RedisConstants;
import com.quanta.demo0.platform.security.model.AuthenticatedUser;
import com.quanta.demo0.platform.security.model.AuthenticationSnapshot;
import com.quanta.demo0.platform.security.properties.JwtProperties;
import com.quanta.demo0.platform.security.properties.QuantabotProperties;
import com.quanta.demo0.platform.security.utils.JwtUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * service token 认证特判（C-5）：
 * bot 的一年期 service token 无 Redis 登录态，必须跳过 validateCurrentSession；
 * 特判仅对 bot 系统账号开放，普通用户伪造 token 也绕不过。
 */
@ExtendWith(MockitoExtension.class)
class TokenAuthenticationServiceImplBotTokenTest {

    private static final String SECRET =
            "test-secret-key-0123456789abcdef0123456789abcdef";

    @Mock
    private JwtProperties jwtProperties;

    @Mock
    private StringRedisTemplate stringRedisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    @Mock
    private AuthenticationSnapshotCache snapshotCache;

    private TokenAuthenticationServiceImpl service;
    private final QuantabotProperties quantabotProperties =
            new QuantabotProperties();

    @BeforeEach
    void setUp() {
        when(jwtProperties.getUserSecretKey()).thenReturn(SECRET);
        lenient().when(stringRedisTemplate.opsForValue())
                .thenReturn(valueOperations);
        lenient().when(valueOperations.get(anyString())).thenReturn(null);
        lenient().when(stringRedisTemplate.hasKey(anyString()))
                .thenReturn(false);

        service = new TokenAuthenticationServiceImpl();
        ReflectionTestUtils.setField(service, "jwtProperties", jwtProperties);
        ReflectionTestUtils.setField(
                service,
                "stringRedisTemplate",
                stringRedisTemplate
        );
        ReflectionTestUtils.setField(
                service,
                "quantabotProperties",
                quantabotProperties
        );
        ReflectionTestUtils.setField(
                service,
                "authenticationSnapshotCache",
                snapshotCache
        );
    }

    private String serviceToken(Long userId) {
        Map<String, Object> claims = new HashMap<>();
        claims.put(JwtClaimsConstant.USER_ID, userId);
        claims.put(JwtClaimsConstant.TOKEN_TYPE,
                JwtClaimsConstant.SERVICE_TOKEN_TYPE);
        return JwtUtil.createJWT(SECRET, 60_000L, claims);
    }

    private AuthenticationSnapshot snapshot(Set<String> roles) {
        return new AuthenticationSnapshot(
                0,
                false,
                roles,
                Set.of(),
                false
        );
    }

    @Test
    void serviceTokenSkipsSessionLookupButStillUsesServiceScopedSnapshot() {
        when(snapshotCache.get(10000L, true))
                .thenReturn(snapshot(Set.of("USER", "BOT")));

        AuthenticatedUser authenticated =
                service.authenticate(serviceToken(10000L));

        assertEquals(10000L, authenticated.getUserId());
        assertTrue(authenticated.getRoles().contains("BOT"));
        assertTrue(authenticated.getRoles().contains("USER"));
        assertFalse(authenticated.getVerified());
        verify(valueOperations, never()).get(
                RedisConstants.LOGIN_USER_KEY + 10000L
        );
        verify(snapshotCache).get(10000L, true);
        verify(stringRedisTemplate).hasKey(
                RedisConstants.USER_BANNED_KEY + 10000L
        );
    }

    @Test
    void serviceTokenForNonBotAccountIsRejectedBeforeSnapshotLookup() {
        TokenAuthenticationException exception = assertThrows(
                TokenAuthenticationException.class,
                () -> service.authenticate(serviceToken(1L))
        );

        assertEquals(
                TokenAuthenticationFailureReason.TOKEN_INVALID,
                exception.getReason()
        );
        verify(snapshotCache, never()).get(1L, true);
    }

    @Test
    void ordinaryTokenWithoutRedisSessionStillFailsBeforeSnapshotLookup() {
        Map<String, Object> claims = new HashMap<>();
        claims.put(JwtClaimsConstant.USER_ID, 10000L);
        String normalToken = JwtUtil.createJWT(SECRET, 60_000L, claims);

        TokenAuthenticationException exception = assertThrows(
                TokenAuthenticationException.class,
                () -> service.authenticate(normalToken)
        );

        assertEquals(
                TokenAuthenticationFailureReason.SESSION_NOT_FOUND,
                exception.getReason()
        );
        verify(snapshotCache, never()).get(10000L, false);
    }

    @Test
    void ordinaryUserSnapshotCannotGainBotRole() {
        String normalToken = normalToken(3L);
        prepareNormalSession(normalToken, 3L);
        when(snapshotCache.get(3L, false))
                .thenReturn(snapshot(Set.of("USER")));

        AuthenticatedUser authenticated = service.authenticate(normalToken);

        assertTrue(authenticated.getRoles().contains("USER"));
        assertFalse(authenticated.getRoles().contains("BOT"));
        verify(snapshotCache).get(3L, false);
    }

    @Test
    void botUserOrdinaryTokenCannotReuseServiceSnapshot() {
        String normalToken = normalToken(10000L);
        prepareNormalSession(normalToken, 10000L);
        when(snapshotCache.get(10000L, false))
                .thenReturn(snapshot(Set.of("USER")));

        AuthenticatedUser authenticated = service.authenticate(normalToken);

        assertTrue(authenticated.getRoles().contains("USER"));
        assertFalse(authenticated.getRoles().contains("BOT"));
        verify(snapshotCache).get(10000L, false);
    }

    private String normalToken(Long userId) {
        Map<String, Object> claims = new HashMap<>();
        claims.put(JwtClaimsConstant.USER_ID, userId);
        return JwtUtil.createJWT(SECRET, 60_000L, claims);
    }

    private void prepareNormalSession(String token, Long userId) {
        when(valueOperations.get(
                RedisConstants.LOGIN_USER_KEY + userId
        )).thenReturn(token);
        when(stringRedisTemplate.hasKey(
                RedisConstants.USER_BANNED_KEY + userId
        )).thenReturn(false);
    }
}
