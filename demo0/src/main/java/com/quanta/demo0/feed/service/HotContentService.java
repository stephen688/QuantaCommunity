package com.quanta.demo0.feed.service;

import java.time.LocalDateTime;

/** 热度流和推荐 ZSET 的内容端口。 */
public interface HotContentService {

    String resolveRecommendKey(Integer contentType);

    String resolveRecommendHotKey(Integer contentType);

    void publishToRedis(Long contentId, LocalDateTime createTime, Integer contentType);

    void reconcileHotScore(Long contentId);
}
