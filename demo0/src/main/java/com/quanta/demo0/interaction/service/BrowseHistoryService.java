package com.quanta.demo0.interaction.service;

import com.github.pagehelper.Page;
import com.quanta.demo0.interaction.vo.BrowseHistorySnapshotVO;

import java.util.List;

/**
 * 互动域浏览历史服务。
 *
 * <p>负责记录、分页读取和清理浏览关系，不承担内容可见性组装。</p>
 */
public interface BrowseHistoryService {

    void recordBrowseHistory(Long contentId);

    Page<Long> pageContentIds(Long userId, Integer current, Integer size);

    /**
     * 按数据库原有增量 SQL 返回首看浏览快照，供 Feed 对账任务转发行为事件。
     *
     * @param watermarkId 仅查询 id 大于该水位的记录
     * @param batchSize   单批最大记录数
     * @return 按 Mapper SQL 保持的 id 升序首看快照
     */
    List<BrowseHistorySnapshotVO> getIncrementalFirstViewSnapshots(Long watermarkId, int batchSize);

    void clearBrowseHistory(Long userId);
}
