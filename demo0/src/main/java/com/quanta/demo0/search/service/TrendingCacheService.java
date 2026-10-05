package com.quanta.demo0.search.service;

import com.quanta.demo0.search.vo.SearchTrendingVO;

import java.util.function.Supplier;

public interface TrendingCacheService {

    /**
     * 读热榜：L1（Caffeine，10s）→ L2（Redis，300s±60s 抖动）→ loader 回源，
     * 回源结果经 Lua 条件回填写回 L2（详见 TrendingCacheServiceImpl）。
     */
    SearchTrendingVO getOrLoad(Supplier<SearchTrendingVO> loader);

    /** 失效两级缓存：清本实例 L1 + 向 L2 写墓碑（不是 DELETE）。 */
    void evict();
}
