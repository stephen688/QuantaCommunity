package com.quanta.demo0.search.service.impl;

import com.quanta.demo0.search.service.TrendingCacheService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
// 热榜缓存失效器，用于在事务提交后失效热榜缓存
@Component
@Slf4j
public class TrendingCacheInvalidator {

    private final TrendingCacheService trendingCacheService;

    public TrendingCacheInvalidator(TrendingCacheService trendingCacheService) {
        this.trendingCacheService = trendingCacheService;
    }
    // 事务提交后失效热榜缓存
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
    // 失效热榜缓存
    private void evict(String reason) {
        trendingCacheService.evict();
        log.info("热榜缓存失效完成，reason={}", reason);
    }
}
