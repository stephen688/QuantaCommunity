package com.quanta.demo0.user.service.impl;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.quanta.demo0.user.vo.UserAuthInfoVO;
import com.quanta.demo0.user.mapper.UserMapper;
import com.quanta.demo0.platform.redis.properties.ReadPathCacheProperties;
import com.quanta.demo0.user.service.AuthorProfileCache;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 作者公开资料的 Caffeine L1 缓存实现。
 *
 * <p>缓存不保存查询不到的作者，避免用户恢复或补全资料后被负缓存阻塞。
 * Mapper 返回的缺失行也不会被补成空作者对象；调用方继续沿用原有空信息回退。</p>
 */
@Service
public class AuthorProfileCacheImpl implements AuthorProfileCache {

    private final UserMapper userMapper;
    private final Cache<Long, UserAuthInfoVO> localCache;

    public AuthorProfileCacheImpl(
            UserMapper userMapper,
            ReadPathCacheProperties properties
    ) {
        this.userMapper = userMapper;
        this.localCache = Caffeine.newBuilder()
                .maximumSize(properties.getAuthor().getMaximumSize())
                .expireAfterWrite(Duration.ofSeconds(properties.getAuthor().getTtlSeconds()))
                .build();
    }

    @Override
    public UserAuthInfoVO get(Long userId) {
        if (userId == null) {
            return null;
        }
        return localCache.get(userId, userMapper::selectUserAuthInfoById);
    }

    @Override
    public Map<Long, UserAuthInfoVO> getAll(Collection<Long> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            return Collections.emptyMap();
        }

        LinkedHashSet<Long> orderedIds = new LinkedHashSet<>();
        for (Long userId : userIds) {
            if (userId != null) {
                orderedIds.add(userId);
            }
        }
        if (orderedIds.isEmpty()) {
            return Collections.emptyMap();
        }

        List<Long> missingIds = new ArrayList<>();
        for (Long userId : orderedIds) {
            if (localCache.getIfPresent(userId) == null) {
                missingIds.add(userId);
            }
        }

        if (!missingIds.isEmpty()) {
            loadMissing(missingIds);
        }

        Map<Long, UserAuthInfoVO> result = new LinkedHashMap<>();
        for (Long userId : orderedIds) {
            UserAuthInfoVO userAuthInfo = localCache.getIfPresent(userId);
            if (userAuthInfo != null) {
                result.put(userId, userAuthInfo);
            }
        }
        return result;
    }

    @Override
    public void evict(Long userId) {
        if (userId != null) {
            localCache.invalidate(userId);
        }
    }

    private void loadMissing(List<Long> missingIds) {
        List<UserAuthInfoVO> loaded = userMapper.selectUserAuthInfoByIds(missingIds);
        if (loaded == null || loaded.isEmpty()) {
            return;
        }

        Set<Long> requestedIds = new HashSet<>(missingIds);
        for (UserAuthInfoVO userAuthInfo : loaded) {
            if (userAuthInfo == null
                    || userAuthInfo.getUserId() == null
                    || !requestedIds.contains(userAuthInfo.getUserId())) {
                continue;
            }
            localCache.asMap().putIfAbsent(userAuthInfo.getUserId(), userAuthInfo);
        }
    }
}
