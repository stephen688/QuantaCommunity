package com.quanta.demo0.service.Impl;

import com.quanta.demo0.security.AuthenticationSnapshotCache;
import com.quanta.demo0.service.AuthorProfileCache;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import static com.quanta.demo0.constant.RedisConstants.SECURITY_VERIFIED_KEY;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class UserReadCacheInvalidatorImplTest {

    private AuthenticationSnapshotCache authenticationCache;
    private AuthorProfileCache authorProfileCache;
    private StringRedisTemplate redisTemplate;
    private UserReadCacheInvalidatorImpl invalidator;

    @BeforeEach
    void setUp() {
        authenticationCache = mock(AuthenticationSnapshotCache.class);
        authorProfileCache = mock(AuthorProfileCache.class);
        redisTemplate = mock(StringRedisTemplate.class);
        invalidator = new UserReadCacheInvalidatorImpl(
                authenticationCache,
                authorProfileCache,
                redisTemplate
        );
    }

    @AfterEach
    void tearDown() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
        TransactionSynchronizationManager.setActualTransactionActive(false);
    }

    @Test
    void evictAllRunsImmediatelyWithoutTransaction() {
        invalidator.evictAllAfterCommit(7L);

        verify(authenticationCache).evict(7L);
        verify(authorProfileCache).evict(7L);
        verify(redisTemplate).delete(SECURITY_VERIFIED_KEY + 7L);
    }

    @Test
    void transactionDefersEvictionUntilCommit() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.initSynchronization();

        invalidator.evictAllAfterCommit(7L);

        verify(authenticationCache, never()).evict(7L);
        verify(authorProfileCache, never()).evict(7L);
        verify(redisTemplate, never()).delete(SECURITY_VERIFIED_KEY + 7L);

        for (TransactionSynchronization synchronization
                : TransactionSynchronizationManager.getSynchronizations()) {
            synchronization.afterCommit();
        }

        verify(authenticationCache).evict(7L);
        verify(authorProfileCache).evict(7L);
        verify(redisTemplate).delete(SECURITY_VERIFIED_KEY + 7L);
    }

    @Test
    void rollbackDoesNotEvict() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.initSynchronization();

        invalidator.evictAllAfterCommit(7L);
        for (TransactionSynchronization synchronization
                : TransactionSynchronizationManager.getSynchronizations()) {
            synchronization.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);
        }

        verify(authenticationCache, never()).evict(7L);
        verify(authorProfileCache, never()).evict(7L);
        verify(redisTemplate, never()).delete(SECURITY_VERIFIED_KEY + 7L);
    }

    @Test
    void targetedEvictionOnlyTouchesRequestedCache() {
        invalidator.evictAuthenticationAfterCommit(7L);

        verify(authenticationCache).evict(7L);
        verify(authorProfileCache, never()).evict(7L);
        verify(redisTemplate, never()).delete(SECURITY_VERIFIED_KEY + 7L);
    }
}
