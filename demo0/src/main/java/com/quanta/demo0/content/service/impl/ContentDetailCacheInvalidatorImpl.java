package com.quanta.demo0.content.service.impl;

import com.quanta.demo0.content.service.ContentDetailCacheInvalidator;
import com.quanta.demo0.content.service.ContentDetailCacheService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Component
@Slf4j
public class ContentDetailCacheInvalidatorImpl implements ContentDetailCacheInvalidator {

    private final ContentDetailCacheService contentDetailCacheService;

    public ContentDetailCacheInvalidatorImpl(ContentDetailCacheService contentDetailCacheService) {
        this.contentDetailCacheService = contentDetailCacheService;
    }

    @Override
    public void evictAfterCommit(Long contentId, String reason) {
        if (contentId == null) {
            return;
        }
        if (TransactionSynchronizationManager.isActualTransactionActive()
                && TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(
                    new TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                            evict(contentId, reason);
                        }
                    }
            );
            return;
        }
        evict(contentId, reason);
    }

    private void evict(Long contentId, String reason) {
        contentDetailCacheService.evict(contentId);
        log.info("帖子详情缓存失效完成，contentId={}，reason={}", contentId, reason);
    }
}
