package com.quanta.demo0.platform.security;

import com.quanta.demo0.platform.security.service.impl.AuthenticationSnapshotCacheImpl;

import com.quanta.demo0.platform.common.enums.AuditStatus;
import com.quanta.demo0.identity.service.IdentityQueryService;
import com.quanta.demo0.identity.vo.UserAuthStatusVO;
import com.quanta.demo0.platform.security.mapper.UserRoleMapper;
import com.quanta.demo0.platform.security.model.AuthenticationSnapshot;
import com.quanta.demo0.platform.security.properties.QuantabotProperties;
import com.quanta.demo0.platform.redis.properties.ReadPathCacheProperties;
import com.quanta.demo0.user.service.UserQueryService;
import com.quanta.demo0.user.vo.UserAccountVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

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

    private UserQueryService userQueryService;
    private IdentityQueryService identityQueryService;
    private UserRoleMapper userRoleMapper;
    private AuthenticationSnapshotCacheImpl cache;

    @BeforeEach
    void setUp() {
        userQueryService = mock(UserQueryService.class);
        identityQueryService = mock(IdentityQueryService.class);
        userRoleMapper = mock(UserRoleMapper.class);

        QuantabotProperties quantabotProperties = new QuantabotProperties();
        ReadPathCacheProperties cacheProperties = new ReadPathCacheProperties();
        cache = new AuthenticationSnapshotCacheImpl(
                userQueryService,
                identityQueryService,
                userRoleMapper,
                quantabotProperties,
                cacheProperties
        );
    }

    @Test
    void firstLoadReadsUserAuthAndRolesThenSecondReadUsesSnapshot() {
        when(userQueryService.getAccount(USER_ID)).thenReturn(user(0));
        when(identityQueryService.getAuthStatus(USER_ID)).thenReturn(
                UserAuthStatusVO.builder()
                        .auditStatus(AuditStatus.APPROVED.getCode())
                        .build()
        );
        when(userRoleMapper.findRoleCodesByUserId(USER_ID))
                .thenReturn(List.of("OPERATIONS_ADMIN"));

        AuthenticationSnapshot first = cache.get(USER_ID, false);
        AuthenticationSnapshot second = cache.get(USER_ID, false);

        assertEquals(first, second);
        verify(userQueryService).getAccount(USER_ID);
        verify(identityQueryService).getAuthStatus(USER_ID);
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
        when(userQueryService.getAccount(USER_ID)).thenReturn(null);

        assertNull(cache.get(USER_ID, false));
        assertNull(cache.get(USER_ID, false));

        verify(userQueryService, org.mockito.Mockito.times(2)).getAccount(USER_ID);
        verify(identityQueryService, never()).getAuthStatus(USER_ID);
        verify(userRoleMapper, never()).findRoleCodesByUserId(USER_ID);
    }

    @Test
    void loaderFailureDoesNotEnterCache() {
        when(userQueryService.getAccount(USER_ID))
                .thenThrow(new IllegalStateException("database unavailable"));

        assertThrows(
                IllegalStateException.class,
                () -> cache.get(USER_ID, false)
        );
        assertThrows(
                IllegalStateException.class,
                () -> cache.get(USER_ID, false)
        );

        verify(userQueryService, org.mockito.Mockito.times(2)).getAccount(USER_ID);
    }

    @Test
    void ordinaryAndServiceTokenUseSeparateKeysAndBotRoleIsRestricted() {
        when(userQueryService.getAccount(USER_ID)).thenReturn(user(0));
        when(identityQueryService.getAuthStatus(USER_ID)).thenReturn(null);
        when(userRoleMapper.findRoleCodesByUserId(USER_ID))
                .thenReturn(List.of("BOT"));

        AuthenticationSnapshot ordinary = cache.get(USER_ID, false);
        AuthenticationSnapshot service = cache.get(USER_ID, true);

        assertFalse(ordinary.roles().contains("BOT"));
        assertFalse(service.roles().contains("BOT"));
        assertNotSame(ordinary, service);
        verify(userQueryService, org.mockito.Mockito.times(2)).getAccount(USER_ID);
    }

    @Test
    void configuredBotServiceSnapshotContainsBotButOrdinarySnapshotDoesNot() {
        when(userQueryService.getAccount(10000L)).thenReturn(user(0));
        when(identityQueryService.getAuthStatus(10000L)).thenReturn(null);
        when(userRoleMapper.findRoleCodesByUserId(10000L))
                .thenReturn(List.of("BOT"));

        AuthenticationSnapshot ordinary = cache.get(10000L, false);
        AuthenticationSnapshot service = cache.get(10000L, true);

        assertFalse(ordinary.roles().contains("BOT"));
        assertTrue(service.roles().contains("BOT"));
        verify(userQueryService, org.mockito.Mockito.times(2)).getAccount(10000L);
    }

    @Test
    void roleAndAuthoritySetsCannotBeMutatedAndEvictClearsBothTokenKinds() {
        when(userQueryService.getAccount(USER_ID)).thenReturn(user(0));
        when(identityQueryService.getAuthStatus(USER_ID)).thenReturn(null);
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
        verify(userQueryService, org.mockito.Mockito.times(4)).getAccount(USER_ID);
        verify(identityQueryService, org.mockito.Mockito.times(4))
                .getAuthStatus(USER_ID);
        verify(userRoleMapper, org.mockito.Mockito.times(4))
                .findRoleCodesByUserId(USER_ID);
    }

    private UserAccountVO user(Integer accountStatus) {
        return UserAccountVO.builder()
                .id(USER_ID)
                .accountStatus(accountStatus)
                .build();
    }
}
