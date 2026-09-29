package com.quanta.demo0.interaction.service.impl;

import com.github.pagehelper.Page;
import com.github.pagehelper.PageHelper;
import com.quanta.demo0.content.exception.ContentFailedException;
import com.quanta.demo0.interaction.entity.BrowseHistory;
import com.quanta.demo0.interaction.mapper.BrowseHistoryMapper;
import com.quanta.demo0.interaction.service.BrowseHistoryService;
import com.quanta.demo0.interaction.vo.BrowseHistorySnapshotVO;
import com.quanta.demo0.platform.security.context.BaseContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;

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
     * 查询浏览对账首看增量并转换为跨域稳定快照，保留 Mapper 的排序与分页语义。
     */
    @Override
    public List<BrowseHistorySnapshotVO> getIncrementalFirstViewSnapshots(Long watermarkId, int batchSize) {
        long safeWatermarkId = watermarkId == null || watermarkId < 0 ? 0L : watermarkId;
        int safeBatchSize = batchSize <= 0 ? 500 : batchSize;
        List<BrowseHistory> rows = browseHistoryMapper.selectIncrementalFirstViews(
                safeWatermarkId, safeBatchSize);
        if (rows == null || rows.isEmpty()) {
            return List.of();
        }
        return rows.stream()
                .filter(row -> row != null)
                .map(row -> BrowseHistorySnapshotVO.builder()
                        .id(row.getId())
                        .userId(row.getUserId())
                        .contentId(row.getContentId())
                        .browseDate(row.getBrowseDate())
                        .createTime(row.getCreateTime())
                        .updateTime(row.getUpdateTime())
                        .isDeleted(row.getIsDeleted())
                        .build())
                .toList();
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
