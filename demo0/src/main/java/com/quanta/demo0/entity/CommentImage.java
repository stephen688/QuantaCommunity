package com.quanta.demo0.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 评论图片实体类
 * 对应数据库表：tb_comment_image
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class CommentImage implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 图片唯一 ID（主键）
     */
    private Long imageId;

    /**
     * 评论 ID（关联 tb_content_comment.comment_id）
     */
    private Long commentId;

    /**
     * 图片 URL
     */
    private String imageUrl;

    /**
     * 排序（数值越小越靠前）
     */
    private Integer sort;

    /**
     * 创建时间
     */
    private LocalDateTime createTime;

}