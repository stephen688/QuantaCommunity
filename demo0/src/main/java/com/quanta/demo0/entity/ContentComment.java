package com.quanta.demo0.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 内容评论实体类
 * 对应数据库表：tb_content_comment
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class ContentComment implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 评论唯一 ID（主键）
     */
    private Long commentId;

    /**
     * 内容 ID（关联 tb_content.content_id）
     */
    private Long contentId;

    /**
     * 回答 ID（关联 tb_answer.answer_id，可选）
     */
    private Long answerId;

    /**
     * 父评论 ID（顶级评论为 null 或 0）
     */
    private Long parentId;

    /**
     * 被回复的评论 ID（回复评论时使用）
     */
    private Long replyCommentId;

    /**
     * 被回复的用户 ID
     */
    private Long replyUserId;

    /**
     * 评论用户 ID（关联 tb_user.id）
     */
    private Long userId;

    /**
     * 评论内容
     */
    private String content;

    /**
     * 点赞数量
     */
    private Integer likeCount;

    /**
     * 审核状态：0-待审核 1-已通过 2-已驳回
     */
    private Integer auditStatus;

    /**
     * 审核驳回原因
     */
    private String rejectReason;

    /**
     * 审核时间
     */
    private LocalDateTime auditTime;

    /**
     * 审核用户 ID
     */
    private Long auditUserId;

    /**
     * 是否删除：0-未删除 1-已删除
     */
    private Integer isDeleted;

    /**
     * 创建时间
     */
    private LocalDateTime createTime;

    /**
     * 更新时间
     */
    private LocalDateTime updateTime;

}