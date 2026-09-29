package com.quanta.demo0.content.service.impl;

import com.quanta.demo0.content.service.ContentDetailCacheService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class ContentDetailCacheInvalidatorImplTest {

    private ContentDetailCacheService cacheService;
    private ContentDetailCacheInvalidatorImpl invalidator;

    @BeforeEach
    void setUp() {
        cacheService = mock(ContentDetailCacheService.class);
        invalidator = new ContentDetailCacheInvalidatorImpl(cacheService);
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
        invalidator.evictAfterCommit(31L, "manual");

        verify(cacheService).evict(31L);
    }

    @Test
    void activeTransactionEvictsOnlyAfterCommit() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.initSynchronization();

        invalidator.evictAfterCommit(31L, "like");

        verify(cacheService, never()).evict(31L);
        for (TransactionSynchronization synchronization
                : TransactionSynchronizationManager.getSynchronizations()) {
            synchronization.afterCommit();
        }
        verify(cacheService).evict(31L);
    }

    @Test
    void rollbackDoesNotEvict() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.initSynchronization();

        invalidator.evictAfterCommit(31L, "delete");
        for (TransactionSynchronization synchronization
                : TransactionSynchronizationManager.getSynchronizations()) {
            synchronization.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);
        }

        verify(cacheService, never()).evict(31L);
    }

    @Test
    void nullContentIdDoesNothing() {
        invalidator.evictAfterCommit(null, "invalid");

        verify(cacheService, never()).evict(31L);
    }
}
