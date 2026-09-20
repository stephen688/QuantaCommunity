package com.quanta.demo0.service;

public interface UserReadCacheInvalidator {

    void evictAuthenticationAfterCommit(Long userId);

    void evictAuthorAfterCommit(Long userId);

    void evictAllAfterCommit(Long userId);
}
