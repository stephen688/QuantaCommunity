package com.quanta.demo0.follow.service.impl;

import com.quanta.demo0.follow.mapper.FollowMapper;
import com.quanta.demo0.follow.service.FollowQueryService;
import com.quanta.demo0.platform.redis.constant.RedisConstants;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 关注关系查询实现。
 *
 * 职责：读取关注统计和 Redis 关系集合；
 * 边界：不修改关注关系，也不触发 Feed 或通知副作用。
 */
@Service
@RequiredArgsConstructor
public class FollowQueryServiceImpl implements FollowQueryService {

    private final FollowMapper followMapper;
    private final StringRedisTemplate stringRedisTemplate;

    /**
     * 查询关注数量。
     */
    @Override
    public Integer countFollowing(Long userId) {
        return followMapper.countFollowing(userId);
    }

    /**
     * 查询粉丝数量。
     */
    @Override
    public Integer countFollowers(Long userId) {
        return followMapper.countFollowers(userId);
    }

    /**
     * 查询查看者是否关注目标用户。
     */
    @Override
    public boolean isFollowing(Long viewerId, Long targetUserId) {
        if (viewerId == null || targetUserId == null || viewerId.equals(targetUserId)) {
            return false;
        }
        String key = RedisConstants.FOLLOWED_KEY + viewerId;
        return Boolean.TRUE.equals(
                stringRedisTemplate.opsForSet().isMember(key, targetUserId.toString()));
    }

    /**
     * 查询指定发布者的粉丝 ID。
     */
    @Override
    public List<Long> getFollowerIds(Long publishUserId) {
        return followMapper.selectFollowerIds(publishUserId);
    }

    /**
     * 查询指定用户关注的用户 ID。
     */
    @Override
    public List<Long> getFollowedUserIds(Long userId) {
        return followMapper.selectFollowUserIds(userId);
    }
}
