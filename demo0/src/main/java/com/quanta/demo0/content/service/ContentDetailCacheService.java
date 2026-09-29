package com.quanta.demo0.content.service;

import com.quanta.demo0.content.vo.ContentDetailCacheEntry;

import java.util.function.Supplier;

public interface ContentDetailCacheService {

    ContentDetailCacheEntry getOrLoad(Long contentId, Supplier<ContentDetailCacheEntry> loader);

    void evict(Long contentId);
}
