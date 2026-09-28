package com.quanta.demo0.service.Impl;

import com.quanta.demo0.platform.security.service.AuthenticationSnapshotCache;
import com.quanta.demo0.service.AuthorProfileCache;
import com.quanta.demo0.service.UserReadCacheInvalidator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import static com.quanta.demo0.platform.redis.constant.RedisConstants.SECURITY_VERIFIED_KEY;

@Component
@Slf4j
public class UserReadCacheInvalidatorImpl implements UserReadCacheInvalidator {

    private final AuthenticationSnapshotCache authenticationSnapshotCache;
    private final AuthorProfileCache authorProfileCache;
    private final StringRedisTemplate stringRedisTemplate;

    public UserReadCacheInvalidatorImpl(
            AuthenticationSnapshotCache authenticationSnapshotCache,
            AuthorProfileCache authorProfileCache,
            StringRedisTemplate stringRedisTemplate
    ) {
        this.authenticationSnapshotCache = authenticationSnapshotCache;
        this.authorProfileCache = authorProfileCache;
        this.stringRedisTemplate = stringRedisTemplate;
    }

    @Override
    public void evictAuthenticationAfterCommit(Long userId) {
        afterCommit(userId, () -> authenticationSnapshotCache.evict(userId));
    }

    @Override
    public void evictAuthorAfterCommit(Long userId) {
        afterCommit(userId, () -> authorProfileCache.evict(userId));
    }

    @Override
    public void evictAllAfterCommit(Long userId) {
        afterCommit(userId, () -> {
            authenticationSnapshotCache.evict(userId);
            authorProfileCache.evict(userId);
            try {
                stringRedisTemplate.delete(SECURITY_VERIFIED_KEY + userId);
            } catch (Exception exception) {
                log.warn(
                        "用户读缓存中的认证状态 Redis Key 删除失败，userId={}，message={}",
                        userId,
                        exception.getMessage()
                );
            }
        });
    }

    private void afterCommit(Long userId, Runnable action) {
        if (userId == null) {
            return;
        }
        if (TransactionSynchronizationManager.isActualTransactionActive()
                && TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(
                    new TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                            action.run();
                        }
                    }
            );
            return;
        }
        action.run();
    }
}
