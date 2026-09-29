package com.quanta.demo0.content.service;

public interface ContentDetailCacheInvalidator {

    void evictAfterCommit(Long contentId, String reason);
}
