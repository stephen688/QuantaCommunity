package com.quanta.demo0.search.service.impl;

import com.quanta.demo0.platform.security.context.BaseContext;
import com.quanta.demo0.search.exception.SearchFailedException;
import com.quanta.demo0.search.mapper.SearchMapper;
import com.quanta.demo0.search.service.SearchService;
import com.quanta.demo0.search.service.TrendingCacheService;
import com.quanta.demo0.search.vo.SearchTrendingVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 搜索服务实现类。
 *
 * 核心职责：
 * 1. 提供搜索历史查询、删除与清空等用户侧搜索管理能力；
 * 2. 聚合热门问题、热门校友、搜索热词等发现类数据；
 * 3. 协调数据库与 Redis 缓存，提升高频搜索场景响应性能。
 *
 * 设计说明：
 * - 历史记录以数据库为准，缓存用于热点榜单加速与降压；
 * - 对搜索输入与返回结果做统一校验，避免脏数据透出。
 *
 * ============================================================
 * 【历史为什么软删除？热搜词榜为什么会受"清空历史"影响？】
 * ============================================================
 * 三种删除都走 is_deleted=1 软删（SQL 见 SearchMapper.xml），好处有二：
 * 一是误删可恢复、留有痕迹；二是热搜词榜（selectHotKeywords）统计的正是
 * 全站 is_deleted=0 的历史记录数，用户清空历史会让对应词的热度同步"退烧"。
 * 还要注意三张榜不同源：热词榜直接聚合 MySQL 搜索历史表；
 * 热门问题/热门校友榜读取 Redis ZSET 排行（RECOMMEND_HOT_ALL_KEY /
 * USER_FOLLOWER_RANK_KEY），由 TrendingDataLoader 聚合并兜底 MySQL TopN。
 */
@Service
@Slf4j
public class SearchServiceImpl implements SearchService {

    @Autowired
    private SearchMapper searchMapper;
    @Autowired
    private TrendingCacheService trendingCacheService;
    @Autowired
    private TrendingDataLoader trendingDataLoader;
    /**
     * 当前用户的最近搜索词：按 create_time 倒序，去重后最多返回 20 条。
     *
     * <p>【去重/截断为什么放 Java 侧？】正常情况下同一 (userId, keyword) 只有一行
     * （写入侧先查后插，见 ContentSearchServiceImpl.recordHistory），SQL 层面不会重复；
     * distinct + limit(20) 是应用侧的口径兜底，也让"最多 20 条"这种展示规则
     * 集中在业务代码里而不是埋进 SQL。</p>
     */
    @Override
    public List<String> getSearchHistoryKeywords() {
        //1. 获取当前用户ID
        Long userId = BaseContext.getCurrentId();
        //2.查询数据库搜索历史
       List<String>history= searchMapper.selectSearchHistoryKeywords(userId);
       //3.去重，且限制前20条
        return history.stream().
                distinct().limit(20).toList();

    }

    /**
     * 清空当前用户全部搜索历史（软删，见类注释对软删的解释）。
     */
    @Override
    public void clearSearchHistory() {
        //1. 获取当前用户ID
        Long userId = BaseContext.getCurrentId();
        //2.删除数据库搜索历史
       int rows= searchMapper.softDeleteAllByUserId(userId);

       log.info("清除搜索历史，用户ID: {}, 删除记录数: {}", userId, rows);

    }

    /**
     * 删除指定一条搜索历史。
     *
     * <p>【防越权】SQL 同时限定 id 与 user_id 两个条件（softDeleteSearchHistoryByIdAndUserId），
     * rows=0 意味着"记录不存在"或"不属于当前用户"，两种情况统一抛
     * SearchFailedException——不区分失败原因，避免向调用方泄露他人数据的存在性。</p>
     */
    @Override
    public void deleteOneSearchHistory(Long id) {
        if (id == null) {
            throw new SearchFailedException("搜索历史ID不能为空");
        }
        //1. 获取当前用户ID
        Long userId = BaseContext.getCurrentId();
        //2.删除数据库搜索历史
      int rows=  searchMapper.softDeleteSearchHistoryByIdAndUserId(id,userId);
        if(rows==0){
            throw new SearchFailedException("删除搜索历史失败");
        }
    }


    /**
     *  搜索热门
     * @return
     */

    /**
     * 三榜聚合入口：热门关键词 + 热门问题 + 热门校友。
     *
     * <p>本方法只做一行委托：TrendingCacheService.getOrLoad 内部按
     * L1 Caffeine（TTL 10s）→ L2 Redis（TTL 300s ± 60s 抖动）→ 数据源的顺序取数，
     * miss 时才执行 trendingDataLoader::load 真正聚合。缓存细节不进本类。</p>
     */
    @Override
    public SearchTrendingVO getTrending() {
        return trendingCacheService.getOrLoad(trendingDataLoader::load);
    }
}
