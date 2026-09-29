package com.quanta.demo0.content.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 内容域向其他业务域暴露的最小内容快照。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ContentSnapshotVO {
    private Long contentId;
    private Integer contentType;
    private String title;
    private String content;
    private Long publishUserId;
    private Integer auditStatus;
    private Integer isDeleted;
    private LocalDateTime createTime;
    private Integer likedCount;
    private Integer commentCount;
    private Integer collectCount;
}
