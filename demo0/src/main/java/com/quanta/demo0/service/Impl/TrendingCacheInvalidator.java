package com.quanta.demo0.service.Impl;

import com.quanta.demo0.service.TrendingCacheService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Component
@Slf4j
public class TrendingCacheInvalidator {

    private final TrendingCacheService trendingCacheService;

    public TrendingCacheInvalidator(TrendingCacheService trendingCacheService) {
        this.trendingCacheService = trendingCacheService;
    }

    public void evictAfterCommit(String reason) {
        if (TransactionSynchronizationManager.isActualTransactionActive()
                && TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(
                    new TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                            evict(reason);
                        }
                    }
            );
            return;
        }

        evict(reason);
    }

    private void evict(String reason) {
        trendingCacheService.evict();
        log.info("热榜缓存失效完成，reason={}", reason);
    }
}
