package com.quanta.demo0.mapper;

import com.github.pagehelper.Page;
import com.quanta.demo0.entity.BrowseHistory;
import com.quanta.demo0.entity.Content;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface BrowseHistoryMapper {
    void insertOrUpdateBrowseHistory(BrowseHistory browseHistory);

    Page<Long> selectBrowseHistoryContentIds(Long userId);

    void clearBrowseHistory(Long userId);
}