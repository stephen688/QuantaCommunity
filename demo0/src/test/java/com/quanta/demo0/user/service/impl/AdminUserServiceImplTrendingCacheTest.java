package com.quanta.demo0.user.service.impl;

import com.quanta.demo0.user.entity.User;
import com.quanta.demo0.platform.common.exception.NoFoundException;
import com.quanta.demo0.mapper.UserMapper;
import com.quanta.demo0.platform.audit.service.AdminAuditRecorder;
import com.quanta.demo0.user.service.UserReadCacheInvalidator;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.Answers;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AdminUserServiceImplTrendingCacheTest {

    private enum Operation {
        BAN {
            @Override
            void invoke(AdminUserServiceImpl service, Long userId) {
                service.banUser(userId);
            }
        },
        UNBAN {
            @Override
            void invoke(AdminUserServiceImpl service, Long userId) {
                service.unbanUser(userId);
            }
        };

        abstract void invoke(AdminUserServiceImpl service, Long userId);
    }

    @ParameterizedTest
    @EnumSource(Operation.class)
    void successfulAccountStatusChangeRegistersTrendingInvalidation(Operation operation) {
        Fixture fixture = fixture();
        when(fixture.userMapper.getById(7L)).thenReturn(existingUser(7L));
        when(fixture.userMapper.updateById(any(User.class))).thenReturn(1);

        assertThatNoException().isThrownBy(() -> operation.invoke(fixture.service, 7L));

        verify(fixture.invalidator).evictAfterCommit("account-" + operation.name().toLowerCase());
    }

    @ParameterizedTest
    @EnumSource(Operation.class)
    void missingUserDoesNotInvalidateTrending(Operation operation) {
        Fixture fixture = fixture();
        when(fixture.userMapper.getById(7L)).thenReturn(null);

        assertThatThrownBy(() -> operation.invoke(fixture.service, 7L))
                .isInstanceOf(NoFoundException.class);

        verify(fixture.invalidator, never()).evictAfterCommit(any());
    }

    @ParameterizedTest
    @EnumSource(Operation.class)
    void failedAccountStatusUpdateDoesNotInvalidateTrending(Operation operation) {
        Fixture fixture = fixture();
        when(fixture.userMapper.getById(7L)).thenReturn(existingUser(7L));
        doThrow(new IllegalStateException("database unavailable"))
                .when(fixture.userMapper).updateById(any(User.class));

        assertThatThrownBy(() -> operation.invoke(fixture.service, 7L))
                .isInstanceOf(IllegalStateException.class);

        verify(fixture.invalidator, never()).evictAfterCommit(any());
    }

    private Fixture fixture() {
        AdminUserServiceImpl service = new AdminUserServiceImpl();
        UserMapper userMapper = mock(UserMapper.class);
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class, Answers.RETURNS_DEEP_STUBS);
        AdminAuditRecorder auditRecorder = mock(AdminAuditRecorder.class);
        TrendingCacheInvalidator invalidator = mock(TrendingCacheInvalidator.class);
        UserReadCacheInvalidator userReadCacheInvalidator = mock(UserReadCacheInvalidator.class);
        ReflectionTestUtils.setField(service, "userMapper", userMapper);
        ReflectionTestUtils.setField(service, "stringRedisTemplate", redisTemplate);
        ReflectionTestUtils.setField(service, "adminAuditRecorder", auditRecorder);
        ReflectionTestUtils.setField(service, "trendingCacheInvalidator", invalidator);
        ReflectionTestUtils.setField(service, "userReadCacheInvalidator", userReadCacheInvalidator);
        return new Fixture(service, userMapper, invalidator);
    }

    private User existingUser(Long userId) {
        User user = new User();
        user.setId(userId);
        user.setAccountStatus(0);
        return user;
    }

    private record Fixture(
            AdminUserServiceImpl service,
            UserMapper userMapper,
            TrendingCacheInvalidator invalidator
    ) {
    }
}
