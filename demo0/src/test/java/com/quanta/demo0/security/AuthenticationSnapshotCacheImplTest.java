package com.quanta.demo0.security;

import com.quanta.demo0.entity.User;
import com.quanta.demo0.entity.UserAuth;
import com.quanta.demo0.enums.AuditStatus;
import com.quanta.demo0.mapper.UserMapper;
import com.quanta.demo0.mapper.UserRoleMapper;
import com.quanta.demo0.platform.security.model.AuthenticationSnapshot;
import com.quanta.demo0.properties.QuantabotProperties;
import com.quanta.demo0.properties.ReadPathCacheProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AuthenticationSnapshotCacheImplTest {

    private static final Long USER_ID = 7L;

    private UserMapper userMapper;
    private UserRoleMapper userRoleMapper;
    private AuthenticationSnapshotCacheImpl cache;

    @BeforeEach
    void setUp() {
        userMapper = mock(UserMapper.class);
        userRoleMapper = mock(UserRoleMapper.class);

        QuantabotProperties quantabotProperties = new QuantabotProperties();
        ReadPathCacheProperties cacheProperties = new ReadPathCacheProperties();
        cache = new AuthenticationSnapshotCacheImpl(
                userMapper,
                userRoleMapper,
                quantabotProperties,
                cacheProperties
        );
    }

    @Test
    void firstLoadReadsUserAuthAndRolesThenSecondReadUsesSnapshot() {
        when(userMapper.getById(USER_ID)).thenReturn(user(0));
        when(userMapper.getUserAuthByUserId(USER_ID)).thenReturn(
                UserAuth.builder()
                        .userId(USER_ID)
                        .auditStatus(AuditStatus.APPROVED.getCode())
                        .build()
        );
        when(userRoleMapper.findRoleCodesByUserId(USER_ID))
                .thenReturn(List.of("OPERATIONS_ADMIN"));

        AuthenticationSnapshot first = cache.get(USER_ID, false);
        AuthenticationSnapshot second = cache.get(USER_ID, false);

        assertEquals(first, second);
        verify(userMapper).getById(USER_ID);
        verify(userMapper).getUserAuthByUserId(USER_ID);
        verify(userRoleMapper).findRoleCodesByUserId(USER_ID);
        assertTrue(first.verified());
        assertTrue(first.roles().contains("USER"));
        assertTrue(first.roles().contains("VERIFIED_USER"));
        assertTrue(first.roles().contains("OPERATIONS_ADMIN"));
        assertTrue(first.authorities().contains("USER_BAN"));
        assertFalse(first.admin());
    }

    @Test
    void unknownUserDoesNotEnterCache() {
        when(userMapper.getById(USER_ID)).thenReturn(null);

        assertNull(cache.get(USER_ID, false));
        assertNull(cache.get(USER_ID, false));

        verify(userMapper, org.mockito.Mockito.times(2)).getById(USER_ID);
        verify(userMapper, never()).getUserAuthByUserId(USER_ID);
        verify(userRoleMapper, never()).findRoleCodesByUserId(USER_ID);
    }

    @Test
    void loaderFailureDoesNotEnterCache() {
        when(userMapper.getById(USER_ID))
                .thenThrow(new IllegalStateException("database unavailable"));

        assertThrows(
                IllegalStateException.class,
                () -> cache.get(USER_ID, false)
        );
        assertThrows(
                IllegalStateException.class,
                () -> cache.get(USER_ID, false)
        );

        verify(userMapper, org.mockito.Mockito.times(2)).getById(USER_ID);
    }

    @Test
    void ordinaryAndServiceTokenUseSeparateKeysAndBotRoleIsRestricted() {
        when(userMapper.getById(USER_ID)).thenReturn(user(0));
        when(userMapper.getUserAuthByUserId(USER_ID)).thenReturn(null);
        when(userRoleMapper.findRoleCodesByUserId(USER_ID))
                .thenReturn(List.of("BOT"));

        AuthenticationSnapshot ordinary = cache.get(USER_ID, false);
        AuthenticationSnapshot service = cache.get(USER_ID, true);

        assertFalse(ordinary.roles().contains("BOT"));
        assertFalse(service.roles().contains("BOT"));
        assertNotSame(ordinary, service);
        verify(userMapper, org.mockito.Mockito.times(2)).getById(USER_ID);
    }

    @Test
    void configuredBotServiceSnapshotContainsBotButOrdinarySnapshotDoesNot() {
        when(userMapper.getById(10000L)).thenReturn(user(0));
        when(userMapper.getUserAuthByUserId(10000L)).thenReturn(null);
        when(userRoleMapper.findRoleCodesByUserId(10000L))
                .thenReturn(List.of("BOT"));

        AuthenticationSnapshot ordinary = cache.get(10000L, false);
        AuthenticationSnapshot service = cache.get(10000L, true);

        assertFalse(ordinary.roles().contains("BOT"));
        assertTrue(service.roles().contains("BOT"));
        verify(userMapper, org.mockito.Mockito.times(2)).getById(10000L);
    }

    @Test
    void roleAndAuthoritySetsCannotBeMutatedAndEvictClearsBothTokenKinds() {
        when(userMapper.getById(USER_ID)).thenReturn(user(0));
        when(userMapper.getUserAuthByUserId(USER_ID)).thenReturn(null);
        when(userRoleMapper.findRoleCodesByUserId(USER_ID))
                .thenReturn(List.of("SUPER_ADMIN"));

        AuthenticationSnapshot ordinary = cache.get(USER_ID, false);
        AuthenticationSnapshot service = cache.get(USER_ID, true);
        assertTrue(ordinary.admin());
        assertTrue(ordinary.authorities().contains("ROLE_MANAGE"));
        assertThrows(
                UnsupportedOperationException.class,
                () -> ordinary.roles().add("OTHER")
        );
        assertThrows(
                UnsupportedOperationException.class,
                () -> ordinary.authorities().clear()
        );

        cache.evict(USER_ID);
        cache.get(USER_ID, false);
        cache.get(USER_ID, true);

        assertEquals(2, service.roles().size());
        verify(userMapper, org.mockito.Mockito.times(4)).getById(USER_ID);
        verify(userMapper, org.mockito.Mockito.times(4))
                .getUserAuthByUserId(USER_ID);
        verify(userRoleMapper, org.mockito.Mockito.times(4))
                .findRoleCodesByUserId(USER_ID);
    }

    private User user(Integer accountStatus) {
        return User.builder()
                .id(USER_ID)
                .accountStatus(accountStatus)
                .build();
    }
}
