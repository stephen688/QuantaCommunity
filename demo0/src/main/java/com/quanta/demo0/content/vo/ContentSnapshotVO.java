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
    /** 原始主题 JSON；NULL 与空数组分别表示未处理与无匹配主题。 */
    private String tags;
    private Long publishUserId;
    private Integer auditStatus;
    private Integer isDeleted;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
    private Integer likedCount;
    private Integer commentCount;
    private Integer collectCount;
}
