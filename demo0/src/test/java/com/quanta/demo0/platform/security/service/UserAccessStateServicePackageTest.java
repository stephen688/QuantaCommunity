package com.quanta.demo0.platform.security.service;

import com.quanta.demo0.platform.security.service.impl.UserAccessStateServiceImpl;
import com.quanta.demo0.user.service.UserAccountService;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UserAccessStateServicePackageTest {

    @Test
    void 安全服务位于平台域并通过用户端口查询账号状态() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        UserAccountService userAccountService = mock(UserAccountService.class);
        when(userAccountService.getAccountStatus(7L)).thenReturn(0);

        UserAccessStateService service = new UserAccessStateServiceImpl(redisTemplate, userAccountService);

        assertTrue(service.canReceiveRealtimePush(7L));
        assertEquals("com.quanta.demo0.platform.security.service", UserAccessStateService.class.getPackageName());
        verify(userAccountService).getAccountStatus(7L);
    }
}
