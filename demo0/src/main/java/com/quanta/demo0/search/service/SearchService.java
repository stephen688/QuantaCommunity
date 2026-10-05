package com.quanta.demo0.search.service;


import com.quanta.demo0.search.vo.SearchTrendingVO;

import java.util.List;

/**
 * 搜索域用户侧门面：搜索历史管理（查 / 清 / 删一条）+ 热门发现聚合。
 *
 * <p>实现见 SearchServiceImpl；热门三榜的两级缓存与兜底细节由
 * TrendingCacheService / TrendingDataLoader 承担，接口层面只暴露聚合结果。
 * 用户维度一律取登录态（BaseContext），方法签名不传 userId。</p>
 */
public interface SearchService {
    /** 当前用户最近搜索过的关键词：按时间倒序去重，最多 20 条。 */
    List<String> getSearchHistoryKeywords();

    /** 清空当前用户全部搜索历史（软删）。 */
    void clearSearchHistory();

    /** 删除当前用户的一条搜索历史；记录不存在或不属于本人时抛 SearchFailedException。 */
    void deleteOneSearchHistory(Long id);

    /**
     * 获取搜索热门发现（聚合：热门关键词 + 热门问题 + 热门校友）
     *
     * @return 热门发现聚合数据
     */
    SearchTrendingVO getTrending();
}
