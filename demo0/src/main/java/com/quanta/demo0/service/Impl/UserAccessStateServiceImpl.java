package com.quanta.demo0.service.Impl;

import com.quanta.demo0.constant.RedisConstants;
import com.quanta.demo0.mapper.UserMapper;
import com.quanta.demo0.service.UserAccessStateService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * 用户访问状态服务实现类。
 * Redis用于快速发现刚刚被封禁的用户，
 * 数据库作为最终账号状态来源。
 */
@Service
@Slf4j
public class UserAccessStateServiceImpl
        implements UserAccessStateService {

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private UserMapper userMapper;

    @Override
    public boolean canReceiveRealtimePush(Long userId) {
        if (userId == null) {
            return false;
        }

        String bannedKey = RedisConstants.USER_BANNED_KEY + userId;

        try {
            Boolean banned = stringRedisTemplate.hasKey(bannedKey);

            if (Boolean.TRUE.equals(banned)) {
                return false;
            }
        } catch (Exception exception) {
            /*
             * Redis异常时不能直接认为用户正常，
             * 继续使用数据库状态兜底。
             */
            log.warn("读取Redis封禁状态失败，将回退数据库检查");
        }

        try {
            Integer accountStatus =
                    userMapper.getAccountStatusById(userId);

            /*
             * 只有明确查询到正常状态0才允许推送。
             * 用户不存在、已删除和未知状态都不推送。
             */
            return Integer.valueOf(0)
                    .equals(accountStatus);
        } catch (Exception exception) {
            /*
             * 实时提醒不是核心数据。
             * 状态无法确认时宁可不推送，数据库通知仍然保留。
             */
            log.error("查询用户账号状态失败，跳过实时推送", exception);
            return false;
        }
    }
}
