package com.quanta.demo0.search.service.impl;

import com.quanta.demo0.search.service.TrendingCacheService;
import com.quanta.demo0.search.service.TrendingCacheInvalidator;
import lombok.extern.slf4j.Slf4j;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
/** 搜索域热榜失效器：仅注册提交后失效，不改变调用方事务或事实数据。 */
/*
 * ============================================================
 * 【为什么必须在事务提交后再失效，而不是随写随清？】
 * ============================================================
 * 帖子/回答的审核、删除、粉丝变动等事实写库发生在调用方事务里。
 * 若事务还没提交就 evict：并发请求会立刻回源 MySQL，读到的仍是
 * 提交前的旧数据并回填缓存——提交完成后缓存里反而长期留着旧热榜。
 * 把失效注册到 afterCommit，保证"先让新事实生效，再清缓存"的顺序；
 * 无事务场景（如后台任务、MQ 消费）则退化为立即失效。
 * 目前由 content 域（发布/删除/审核）与 user 域 AdminUserServiceImpl 调用。
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class TrendingCacheInvalidatorImpl implements TrendingCacheInvalidator {

    private final TrendingCacheService trendingCacheService;

    /** 在调用者事务提交后失效；无事务时立即失效。 */
    @Override
    public void evictAfterCommit(String reason) {
        // 两个条件都满足才存在"真正的事务"：只开事务、没开同步时无法注册 afterCommit 回调。
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
