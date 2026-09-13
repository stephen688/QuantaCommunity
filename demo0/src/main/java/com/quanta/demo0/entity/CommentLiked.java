package com.quanta.demo0.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 评论点赞实体类
 * 对应数据库表：tb_comment_like
 * 记录用户对评论的点赞行为
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class CommentLiked implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 点赞记录唯一 ID（主键）
     */
    private Long id;

    /**
     * 评论 ID（关联 tb_content_comment.comment_id）
     */
    private Long commentId;

    /**
     * 点赞用户 ID（关联 tb_user.id）
     */
    private Long userId;

    /**
     * 点赞时间
     */
    private LocalDateTime createTime;




}