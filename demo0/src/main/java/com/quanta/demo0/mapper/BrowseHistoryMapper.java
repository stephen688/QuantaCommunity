package com.quanta.demo0.mapper;

import com.github.pagehelper.Page;
import com.quanta.demo0.entity.BrowseHistory;
import com.quanta.demo0.entity.Content;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface BrowseHistoryMapper {
    void insertOrUpdateBrowseHistory(BrowseHistory browseHistory);

    Page<Long> selectBrowseHistoryContentIds(Long userId);

    void clearBrowseHistory(Long userId);

    /**
     * 浏览对账增量查询（D11）：扫描 id 大于 watermark 的有效行，
     * 且只取每对 (userId, contentId) 的首看行（NOT EXISTS 过滤重复浏览）。
     * 浏览对账任务按 id 升序分批消费，首看语义由 SQL 保证。
     */
    List<BrowseHistory> selectIncrementalFirstViews(@Param("watermarkId") Long watermarkId,
                                                    @Param("batchSize") int batchSize);
}