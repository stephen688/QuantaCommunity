package com.quanta.demo0.service;

import com.quanta.demo0.vo.SearchTrendingVO;

import java.util.function.Supplier;

public interface TrendingCacheService {

    SearchTrendingVO getOrLoad(Supplier<SearchTrendingVO> loader);

    void evict();
}
