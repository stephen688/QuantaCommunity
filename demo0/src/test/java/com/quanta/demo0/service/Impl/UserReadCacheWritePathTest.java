package com.quanta.demo0.service.Impl;

import com.quanta.demo0.dto.UserInfoDTO;
import com.quanta.demo0.entity.BaseContext;
import com.quanta.demo0.mapper.UserMapper;
import com.quanta.demo0.service.UserReadCacheInvalidator;
import com.quanta.demo0.utils.SensitiveWordChecker;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UserReadCacheWritePathTest {

    private static final Long USER_ID = 19L;

    private UserMapper userMapper;
    private UserReadCacheInvalidator invalidator;
    private UserServiceImpl service;

    @BeforeEach
    void setUp() {
        userMapper = mock(UserMapper.class);
        invalidator = mock(UserReadCacheInvalidator.class);
        SensitiveWordChecker sensitiveWordChecker = mock(SensitiveWordChecker.class);
        when(sensitiveWordChecker.replaceSensitiveWords(any())).thenAnswer(invocation -> invocation.getArgument(0));

        service = new UserServiceImpl();
        ReflectionTestUtils.setField(service, "userMapper", userMapper);
        ReflectionTestUtils.setField(service, "sensitiveWordChecker", sensitiveWordChecker);
        ReflectionTestUtils.setField(service, "userReadCacheInvalidator", invalidator);
        BaseContext.setCurrentId(USER_ID);
    }

    @AfterEach
    void tearDown() {
        BaseContext.removeCurrentId();
    }

    @Test
    void successfulProfileUpdateEvictsAuthorCache() {
        when(userMapper.updateById(any())).thenReturn(1);

        service.updateUserInfo(UserInfoDTO.builder().nickName("新昵称").build());

        verify(invalidator).evictAuthorAfterCommit(USER_ID);
    }

    @Test
    void failedProfileUpdateDoesNotEvictAuthorCache() {
        when(userMapper.updateById(any())).thenReturn(0);

        assertThrows(
                RuntimeException.class,
                () -> service.updateUserInfo(UserInfoDTO.builder().nickName("新昵称").build())
        );

        verify(invalidator, never()).evictAuthorAfterCommit(USER_ID);
    }
}
