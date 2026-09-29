package com.quanta.demo0.search.service.impl;

import com.quanta.demo0.search.service.TrendingCacheService;
import com.quanta.demo0.search.service.TrendingCacheInvalidator;
import lombok.extern.slf4j.Slf4j;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
/** 搜索域热榜失效器：仅注册提交后失效，不改变调用方事务或事实数据。 */
@Component
@Slf4j
@RequiredArgsConstructor
public class TrendingCacheInvalidatorImpl implements TrendingCacheInvalidator {

    private final TrendingCacheService trendingCacheService;

    /** 在调用者事务提交后失效；无事务时立即失效。 */
    @Override
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
