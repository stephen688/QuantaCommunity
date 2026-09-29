package com.quanta.demo0.interaction.service;

import com.github.pagehelper.Page;

/**
 * 互动域浏览历史服务。
 *
 * <p>负责记录、分页读取和清理浏览关系，不承担内容可见性组装。</p>
 */
public interface BrowseHistoryService {

    void recordBrowseHistory(Long contentId);

    Page<Long> pageContentIds(Long userId, Integer current, Integer size);

    void clearBrowseHistory(Long userId);
}
