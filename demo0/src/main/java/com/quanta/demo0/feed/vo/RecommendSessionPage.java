package com.quanta.demo0.feed.vo;

import com.quanta.demo0.content.vo.ContentSnapshotVO;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/** 推荐会话页：只承载当前可见快照与游标，作者及访问者状态由HTTP查询服务装配。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RecommendSessionPage {
    private List<ContentSnapshotVO> contents;
    private String feedSessionId;
    private String nextCursor;
    private String recommendationState;
    private Boolean hasMore;
    private Boolean canRevisit;
}
