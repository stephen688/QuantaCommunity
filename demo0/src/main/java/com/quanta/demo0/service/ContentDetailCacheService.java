package com.quanta.demo0.service;

import com.quanta.demo0.vo.ContentDetailCacheEntry;

import java.util.function.Supplier;

public interface ContentDetailCacheService {

    ContentDetailCacheEntry getOrLoad(Long contentId, Supplier<ContentDetailCacheEntry> loader);

    void evict(Long contentId);
}
