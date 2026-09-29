package com.quanta.demo0.user.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

/**
 * 用户域拆包契约测试：登录、资料、身份认证和会话不得继续依赖旧的用户大服务。
 */
class UserServiceSplitPackageTest {

    @Test
    void exposesSeparatedFeatureServices() {
        assertDoesNotThrow(() -> Class.forName("com.quanta.demo0.user.service.UserAccountService"));
        assertDoesNotThrow(() -> Class.forName("com.quanta.demo0.user.service.UserProfileService"));
        assertDoesNotThrow(() -> Class.forName("com.quanta.demo0.identity.service.IdentityService"));
        assertDoesNotThrow(() -> Class.forName("com.quanta.demo0.platform.security.service.SessionService"));
    }
}
