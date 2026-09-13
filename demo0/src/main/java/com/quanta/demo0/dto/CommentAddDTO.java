package com.quanta.demo0.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;


import java.io.Serializable;
import java.util.List;

/**
 * 评论添加 DTO
 * 用于接收前端提交的评论数据
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class CommentAddDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 所属内容 ID（帖子 ID）
     * 必填
     */

    private Long contentId;

    /**
     * 回答 ID（专业问答区使用，生活区传 null）
     * 可选
     */
    private Long answerId;

    /**
     * 父评论 ID
     * 一级评论传 null，二级评论传对应一级评论 ID
     * 必填
     */

    private Long parentId;

    /**
     * 被回复的评论 ID
     * 回复二级评论时传，一级评论不传
     * 可选
     */
    private Long replyCommentId;

    /**
     * 被回复的用户 ID
     * 可选
     */
    private Long replyUserId;

    /**
     * 评论内容
     * 必填，最大 500 字
     */

    private String content;

    /**
     * 评论配图 URL 列表
     * 可选，最多 5 张图片
     */

    private List<String> imageUrls;

}