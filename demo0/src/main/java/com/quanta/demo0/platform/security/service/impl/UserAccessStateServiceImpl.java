package com.quanta.demo0.platform.security.service.impl;

import com.quanta.demo0.platform.redis.constant.RedisConstants;
import com.quanta.demo0.platform.security.service.UserAccessStateService;
import com.quanta.demo0.user.service.UserAccountService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

@Service
@Slf4j
@RequiredArgsConstructor
public class UserAccessStateServiceImpl implements UserAccessStateService {

    private final StringRedisTemplate stringRedisTemplate;
    private final UserAccountService userAccountService;

    @Override
    public boolean canReceiveRealtimePush(Long userId) {
        if (userId == null) {
            return false;
        }

        try {
            Boolean banned = stringRedisTemplate.hasKey(RedisConstants.USER_BANNED_KEY + userId);
            if (Boolean.TRUE.equals(banned)) {
                return false;
            }
        } catch (Exception exception) {
            log.warn("读取Redis封禁状态失败，将回退数据库检查");
        }

        try {
            return Integer.valueOf(0).equals(userAccountService.getAccountStatus(userId));
        } catch (Exception exception) {
            log.error("查询用户账号状态失败，跳过实时推送", exception);
            return false;
        }
    }
}
