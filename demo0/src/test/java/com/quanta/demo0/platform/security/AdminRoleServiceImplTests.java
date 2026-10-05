package com.quanta.demo0.platform.security;

import com.quanta.demo0.content.exception.ContentFailedException;
import com.quanta.demo0.platform.audit.service.AdminAuditRecorder;
import com.quanta.demo0.platform.security.mapper.UserRoleMapper;
import com.quanta.demo0.platform.security.service.impl.AdminRoleServiceImpl;
import com.quanta.demo0.user.service.UserQueryService;
import com.quanta.demo0.user.service.UserReadCacheInvalidator;
import com.quanta.demo0.user.vo.UserAccountVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 管理端角色服务测试。
 *
 * <p>覆盖角色读取的用户存在性边界，避免把不存在用户或持久化故障
 * 静默转换成一个看似正常的空角色列表。</p>
 */
class AdminRoleServiceImplTests {

    private static final Long USER_ID = 42L;

    private UserQueryService userQueryService;
    private UserRoleMapper userRoleMapper;
    private AdminRoleServiceImpl adminRoleService;

    @BeforeEach
    void setUp() {
        userQueryService = mock(UserQueryService.class);
        userRoleMapper = mock(UserRoleMapper.class);

        adminRoleService = new AdminRoleServiceImpl();
        ReflectionTestUtils.setField(
                adminRoleService,
                "userQueryService",
                userQueryService
        );
        ReflectionTestUtils.setField(
                adminRoleService,
                "userRoleMapper",
                userRoleMapper
        );
        ReflectionTestUtils.setField(
                adminRoleService,
                "adminAuditRecorder",
                mock(AdminAuditRecorder.class)
        );
        ReflectionTestUtils.setField(
                adminRoleService,
                "stringRedisTemplate",
                mock(StringRedisTemplate.class)
        );
        ReflectionTestUtils.setField(
                adminRoleService,
                "userReadCacheInvalidator",
                mock(UserReadCacheInvalidator.class)
        );
    }

    @Test
    void getUserRolesReturnsPersistedRolesForExistingUser() {
        when(userQueryService.getAccount(USER_ID))
                .thenReturn(UserAccountVO.builder().id(USER_ID).build());
        when(userRoleMapper.findRoleCodesByUserId(USER_ID))
                .thenReturn(List.of("CONTENT_AUDITOR", "SUPER_ADMIN"));

        List<String> roles = adminRoleService.getUserRoles(USER_ID);

        assertEquals(List.of("CONTENT_AUDITOR", "SUPER_ADMIN"), roles);
        verify(userQueryService).getAccount(USER_ID);
        verify(userRoleMapper).findRoleCodesByUserId(USER_ID);
    }

    @Test
    void getUserRolesRejectsMissingUserBeforeReadingRoles() {
        when(userQueryService.getAccount(USER_ID)).thenReturn(null);

        ContentFailedException exception = assertThrows(
                ContentFailedException.class,
                () -> adminRoleService.getUserRoles(USER_ID)
        );

        assertEquals("用户不存在", exception.getMessage());
        verify(userRoleMapper, never()).findRoleCodesByUserId(USER_ID);
    }
}
