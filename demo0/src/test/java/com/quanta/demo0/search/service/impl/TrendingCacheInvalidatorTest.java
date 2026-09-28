package com.quanta.demo0.search.service.impl;

import com.quanta.demo0.search.service.TrendingCacheService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class TrendingCacheInvalidatorTest {

    private TrendingCacheService trendingCacheService;
    private TrendingCacheInvalidator invalidator;

    @BeforeEach
    void setUp() {
        trendingCacheService = mock(TrendingCacheService.class);
        invalidator = new TrendingCacheInvalidator(trendingCacheService);
    }

    @AfterEach
    void tearDown() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
        TransactionSynchronizationManager.setActualTransactionActive(false);
    }

    @Test
    void evictsImmediatelyWithoutTransaction() {
        invalidator.evictAfterCommit("manual-rebuild");

        verify(trendingCacheService).evict();
    }

    @Test
    void evictsOnlyAfterActiveTransactionCommits() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.initSynchronization();

        invalidator.evictAfterCommit("content-approved");

        verify(trendingCacheService, never()).evict();
        List<TransactionSynchronization> synchronizations =
                TransactionSynchronizationManager.getSynchronizations();
        synchronizations.forEach(TransactionSynchronization::afterCommit);
        verify(trendingCacheService, times(1)).evict();
    }

    @Test
    void rollbackDoesNotEvict() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.initSynchronization();

        invalidator.evictAfterCommit("content-delete");
        TransactionSynchronizationManager.getSynchronizations()
                .forEach(synchronization -> synchronization.afterCompletion(
                        TransactionSynchronization.STATUS_ROLLED_BACK
                ));

        verify(trendingCacheService, never()).evict();
    }
}
