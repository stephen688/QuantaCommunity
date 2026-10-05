package com.quanta.demo0.feed.vo;

import com.quanta.demo0.content.vo.ContentVO;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/** 推荐发现响应：增加会话元数据，保留原热度分页字段，不改变关注流响应。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RecommendPageVO {
    private List<ContentVO> list;
    private Double minScore;
    private Integer offset;
    private Boolean hasMore;
    private String feedSessionId;
    private String nextCursor;
    /** READY为可继续阅读，SEARCHING为尚未扫完，EXHAUSTED为真正耗尽。 */
    private String recommendationState;
    private Boolean canRevisit;
}
