package com.quanta.demo0.content.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 内容域向其他业务域暴露的最小内容快照。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ContentSnapshotVO {
    private Long contentId;
    private Long publishUserId;
    private Integer likedCount;
    private Integer collectCount;
}
