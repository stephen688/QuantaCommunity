package com.quanta.demo0.interaction.service.impl;

import com.github.pagehelper.Page;
import com.github.pagehelper.PageHelper;
import com.quanta.demo0.content.exception.ContentFailedException;
import com.quanta.demo0.interaction.entity.BrowseHistory;
import com.quanta.demo0.interaction.mapper.BrowseHistoryMapper;
import com.quanta.demo0.interaction.service.BrowseHistoryService;
import com.quanta.demo0.platform.security.context.BaseContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;

/**
 * 互动域浏览历史实现。
 *
 * <p>浏览记录失败只降级记录日志，分页与清理仍以数据库结果为准。</p>
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class BrowseHistoryServiceImpl implements BrowseHistoryService {

    private final BrowseHistoryMapper browseHistoryMapper;

    /**
     * 为当前已认证用户记录当天的内容浏览关系；匿名请求不落库。
     */
    @Override
    public void recordBrowseHistory(Long contentId) {
        try {
            Long userId = BaseContext.getCurrentId();
            if (userId == null) {
                return;
            }
            BrowseHistory browseHistory = BrowseHistory.builder()
                    .userId(userId)
                    .contentId(contentId)
                    .browseDate(LocalDate.now())
                    .build();
            browseHistoryMapper.insertOrUpdateBrowseHistory(browseHistory);
        } catch (Exception e) {
            log.error("记录浏览历史失败: contentId={}", contentId, e);
        }
    }

    /**
     * 按浏览时间分页返回指定用户浏览过的内容 ID。
     */
    @Override
    public Page<Long> pageContentIds(Long userId, Integer current, Integer size) {
        if (userId == null) {
            throw new ContentFailedException("userId不能为空");
        }
        int pageNum = current == null || current <= 0 ? 1 : current;
        int pageSize = size == null || size <= 0 ? 10 : size;
        PageHelper.startPage(pageNum, pageSize);
        return browseHistoryMapper.selectBrowseHistoryContentIds(userId);
    }

    /**
     * 清空指定用户的浏览历史。
     */
    @Override
    public void clearBrowseHistory(Long userId) {
        if (userId == null) {
            throw new ContentFailedException("userId不能为空");
        }
        browseHistoryMapper.clearBrowseHistory(userId);
    }
}
