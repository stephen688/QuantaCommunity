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

    @Override
    public void clearSearchHistory() {
        //1. 获取当前用户ID
        Long userId = BaseContext.getCurrentId();
        //2.删除数据库搜索历史
       int rows= searchMapper.softDeleteAllByUserId(userId);

       log.info("清除搜索历史，用户ID: {}, 删除记录数: {}", userId, rows);

    }

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

    @Override
    public SearchTrendingVO getTrending() {
        return trendingCacheService.getOrLoad(trendingDataLoader::load);
    }
}
