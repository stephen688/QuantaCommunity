package com.quanta.demo0.service;

public interface ContentDetailCacheInvalidator {

    void evictAfterCommit(Long contentId, String reason);
}
