package com.quanta.demo0.security;

import com.quanta.demo0.constant.RedisConstants;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;

import static org.mockito.Mockito.*;

/**
 * 阶段1认证状态缓存主动失效测试。
 */
class VerifiedStatusCacheEvictorTests {

    private static final Long USER_ID = 7L;

    private StringRedisTemplate stringRedisTemplate;
    private VerifiedStatusCacheEvictor cacheEvictor;

    @BeforeEach
    void setUp() {
        stringRedisTemplate = mock(StringRedisTemplate.class);
        cacheEvictor = new VerifiedStatusCacheEvictor();
        ReflectionTestUtils.setField(
                cacheEvictor,
                "stringRedisTemplate",
                stringRedisTemplate
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
    void noTransactionDeletesCacheImmediately() {
        cacheEvictor.evictAfterCommit(USER_ID);

        verify(stringRedisTemplate).delete(cacheKey());
    }

    @Test
    void activeTransactionDeletesOnlyAfterCommit() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.initSynchronization();

        cacheEvictor.evictAfterCommit(USER_ID);

        verify(stringRedisTemplate, never()).delete(anyString());

        List<TransactionSynchronization> synchronizations =
                TransactionSynchronizationManager.getSynchronizations();
        synchronizations.forEach(TransactionSynchronization::afterCommit);

        verify(stringRedisTemplate).delete(cacheKey());
    }

    @Test
    void nullUserIdDoesNotDeleteCache() {
        cacheEvictor.evictAfterCommit(null);

        verifyNoInteractions(stringRedisTemplate);
    }

    @Test
    void redisFailureAfterCommitDoesNotEscape() {
        when(stringRedisTemplate.delete(cacheKey()))
                .thenThrow(new IllegalStateException("Redis unavailable"));

        cacheEvictor.evictAfterCommit(USER_ID);

        verify(stringRedisTemplate).delete(cacheKey());
    }

    private String cacheKey() {
        return RedisConstants.SECURITY_VERIFIED_KEY + USER_ID;
    }
}
