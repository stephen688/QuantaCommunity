package com.quanta.demo0.comment.entity;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * CommentMapper.selectReplyCountsByParentIds 的聚合结果行
 * （XML：SELECT parent_id, COUNT(*) AS reply_count ... GROUP BY parent_id）。
 *
 * <p>用强类型行对象而不是 Map&lt;String,Object&gt; 承接 GROUP BY 结果，
 * mapper 签名自描述；调用方 toMap 成 parentId -> 回复数，供一级评论列表防 N+1。</p>
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
public class ReplyCountRow {
    /**
     * 父评论ID
     */
    private Long parentId;
    /**
     * 回复数量
     */
    private Long replyCount;
}
