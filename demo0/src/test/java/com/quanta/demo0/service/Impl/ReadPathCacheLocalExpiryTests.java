package com.quanta.demo0.service.Impl;

import com.quanta.demo0.constant.RoleConstants;
import com.quanta.demo0.entity.User;
import com.quanta.demo0.entity.UserAuthInfo;
import com.quanta.demo0.mapper.UserMapper;
import com.quanta.demo0.mapper.UserRoleMapper;
import com.quanta.demo0.platform.security.properties.QuantabotProperties;
import com.quanta.demo0.platform.redis.properties.ReadPathCacheProperties;
import com.quanta.demo0.security.AuthenticationSnapshotCacheImpl;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 本地缓存跨实例边界：当前实例驱逐立即生效，另一实例通过配置的 TTL 收敛。
 * 使用真实 Caffeine 和两套独立缓存；仅替换数据库读取，将 TTL 缩至一秒验证配置行为。
 */
class ReadPathCacheLocalExpiryTests {

    private static final Long USER_ID = 19L;

    @Test
    void authenticationEvictionIsLocalAndOtherInstanceRefreshesAfterConfiguredTtl() throws Exception {
        UserMapper userMapper = mock(UserMapper.class);
        UserRoleMapper roleMapper = mock(UserRoleMapper.class);
        AtomicReference<List<String>> assignedRoles = new AtomicReference<>(List.of());
        when(userMapper.getById(USER_ID)).thenReturn(User.builder()
                .id(USER_ID).accountStatus(0).build());
        when(roleMapper.findRoleCodesByUserId(USER_ID))
                .thenAnswer(invocation -> assignedRoles.get());
        ReadPathCacheProperties properties = shortTtlProperties();
        AuthenticationSnapshotCacheImpl first = new AuthenticationSnapshotCacheImpl(
                userMapper, roleMapper, new QuantabotProperties(), properties);
        AuthenticationSnapshotCacheImpl second = new AuthenticationSnapshotCacheImpl(
                userMapper, roleMapper, new QuantabotProperties(), properties);

        assertFalse(first.get(USER_ID, false).roles().contains(RoleConstants.OPERATIONS_ADMIN));
        assertFalse(second.get(USER_ID, false).roles().contains(RoleConstants.OPERATIONS_ADMIN));
        assignedRoles.set(List.of(RoleConstants.OPERATIONS_ADMIN));
        first.evict(USER_ID);

        assertTrue(first.get(USER_ID, false).roles().contains(RoleConstants.OPERATIONS_ADMIN));
        assertFalse(second.get(USER_ID, false).roles().contains(RoleConstants.OPERATIONS_ADMIN));
        awaitRefresh(() -> second.get(USER_ID, false).roles().contains(RoleConstants.OPERATIONS_ADMIN));
    }

    @Test
    void authorEvictionIsLocalAndOtherInstanceRefreshesAfterConfiguredTtl() throws Exception {
        UserMapper userMapper = mock(UserMapper.class);
        AtomicReference<UserAuthInfo> profile = new AtomicReference<>(author("旧昵称"));
        when(userMapper.selectUserAuthInfoById(USER_ID)).thenAnswer(invocation -> profile.get());
        ReadPathCacheProperties properties = shortTtlProperties();
        AuthorProfileCacheImpl first = new AuthorProfileCacheImpl(userMapper, properties);
        AuthorProfileCacheImpl second = new AuthorProfileCacheImpl(userMapper, properties);

        assertEquals("旧昵称", first.get(USER_ID).getNickName());
        assertEquals("旧昵称", second.get(USER_ID).getNickName());
        profile.set(author("新昵称"));
        first.evict(USER_ID);

        assertEquals("新昵称", first.get(USER_ID).getNickName());
        assertEquals("旧昵称", second.get(USER_ID).getNickName());
        awaitRefresh(() -> "新昵称".equals(second.get(USER_ID).getNickName()));
    }

    private static ReadPathCacheProperties shortTtlProperties() {
        ReadPathCacheProperties properties = new ReadPathCacheProperties();
        properties.getAuthentication().setTtlSeconds(1L);
        properties.getAuthor().setTtlSeconds(1L);
        return properties;
    }

    private static UserAuthInfo author(String nickName) {
        return UserAuthInfo.builder().userId(USER_ID).nickName(nickName).build();
    }

    private static void awaitRefresh(BooleanSupplier refreshed) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (!refreshed.getAsBoolean() && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        assertTrue(refreshed.getAsBoolean(), "the configured local TTL must force a fresh database load");
    }
}
