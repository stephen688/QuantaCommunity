package com.quanta.demo0.comment.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 评论域向其他业务域暴露的最小评论事实快照。
 *
 * <p>跨域调用只依赖该 VO，不直接引用评论实体或 CommentMapper。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CommentSnapshotVO {

    private Long commentId;
    private Long contentId;
    private Long answerId;
    private Long parentId;
    private Long replyCommentId;
    private Long replyUserId;
    private Long userId;
    private String content;
    private Integer likeCount;
    private Integer auditStatus;
    private Integer isDeleted;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
