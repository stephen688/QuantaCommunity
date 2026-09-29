package com.quanta.demo0.interaction.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 互动域向对账任务暴露的浏览历史稳定快照。
 *
 * <p>Feed 对账只依赖行 ID、用户 ID 和内容 ID；保留时间与删除字段便于
 * 诊断和未来重建，但不向其他域暴露 BrowseHistory 持久化实体。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BrowseHistorySnapshotVO {

    private Long id;
    private Long userId;
    private Long contentId;
    private LocalDate browseDate;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
    private Integer isDeleted;
}
