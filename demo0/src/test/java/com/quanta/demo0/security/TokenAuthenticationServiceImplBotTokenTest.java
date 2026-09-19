package com.quanta.demo0.security;

import com.quanta.demo0.constant.JwtClaimsConstant;
import com.quanta.demo0.constant.RedisConstants;
import com.quanta.demo0.entity.User;
import com.quanta.demo0.mapper.UserMapper;
import com.quanta.demo0.mapper.UserRoleMapper;
import com.quanta.demo0.properties.JwtProperties;
import com.quanta.demo0.properties.QuantabotProperties;
import com.quanta.demo0.utils.JwtUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
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
    private UserMapper userMapper;

    @Mock
    private UserRoleMapper userRoleMapper;

    @InjectMocks
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

        ReflectionTestUtils.setField(
                service,
                "quantabotProperties",
                quantabotProperties
        );
    }

    private String serviceToken(Long userId) {
        Map<String, Object> claims = new HashMap<>();
        claims.put(JwtClaimsConstant.USER_ID, userId);
        claims.put(JwtClaimsConstant.TOKEN_TYPE,
                JwtClaimsConstant.SERVICE_TOKEN_TYPE);
        return JwtUtil.createJWT(SECRET, 60_000L, claims);
    }

    private User botUser() {
        User user = new User();
        user.setId(10000L);
        user.setNickName("框框");
        user.setAccountStatus(0);
        return user;
    }

    @Test
    void serviceToken免Redis会话校验_角色含BOT() {
        when(userMapper.getById(10000L)).thenReturn(botUser());
        when(userMapper.getUserAuthByUserId(10000L)).thenReturn(null);
        when(userRoleMapper.findRoleCodesByUserId(10000L))
                .thenReturn(List.of("BOT"));

        AuthenticatedUser authenticated =
                service.authenticate(serviceToken(10000L));

        assertEquals(10000L, authenticated.getUserId());
        assertTrue(authenticated.getRoles().contains("BOT"));
        assertTrue(authenticated.getRoles().contains("USER"));
        assertFalse(authenticated.getVerified());
    }

    @Test
    void serviceToken用于非bot账号_拒绝() {
        TokenAuthenticationException exception = assertThrows(
                TokenAuthenticationException.class,
                () -> service.authenticate(serviceToken(1L))
        );

        assertEquals(
                TokenAuthenticationFailureReason.TOKEN_INVALID,
                exception.getReason()
        );
    }

    @Test
    void 普通token无Redis会话_仍报会话失效() {
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
    }

    @Test
    void 普通用户即使数据库误授BOT角色_也不得获得BOT() {
        String normalToken = normalToken(3L);
        prepareNormalSession(normalToken, 3L);
        when(userMapper.getById(3L)).thenReturn(user(3L));
        when(userRoleMapper.findRoleCodesByUserId(3L))
                .thenReturn(List.of("BOT"));

        AuthenticatedUser authenticated = service.authenticate(normalToken);

        assertTrue(authenticated.getRoles().contains("USER"));
        assertFalse(authenticated.getRoles().contains("BOT"));
    }

    @Test
    void bot用户普通token即使数据库有BOT角色_也不得获得BOT() {
        String normalToken = normalToken(10000L);
        prepareNormalSession(normalToken, 10000L);
        when(userMapper.getById(10000L)).thenReturn(botUser());
        when(userRoleMapper.findRoleCodesByUserId(10000L))
                .thenReturn(List.of("BOT"));

        AuthenticatedUser authenticated = service.authenticate(normalToken);

        assertTrue(authenticated.getRoles().contains("USER"));
        assertFalse(authenticated.getRoles().contains("BOT"));
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

    private User user(Long userId) {
        User user = new User();
        user.setId(userId);
        user.setNickName("测试用户");
        user.setAccountStatus(0);
        return user;
    }
}
