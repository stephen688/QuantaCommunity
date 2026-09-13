package com.quanta.demo0.service;


import com.quanta.demo0.vo.SearchTrendingVO;

import java.util.List;

public interface SearchService {
    List<String> getSearchHistoryKeywords();

    void clearSearchHistory();

    void deleteOneSearchHistory(Long id);

    /**
     * 获取搜索热门发现（聚合：热门关键词 + 热门问题 + 热门校友）
     *
     * @return 热门发现聚合数据
     */
    SearchTrendingVO getTrending();
}
