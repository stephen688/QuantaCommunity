package com.quanta.demo0.dto;

import lombok.Data;

import java.io.Serializable;

@Data
public class ReplyPageDTO implements Serializable {

    /**
     * 一级评论 ID（必填）
     */
    private Long parentCommentId;

    /**
     * 内容 ID（必填）
     */
    private Long contentId;

    /**
     * 回答 ID（可选）
     */
    private Long answerId;

    /**
     * 当前页码（默认 1）
     */
    private Integer pageNum = 1;

    /**
     * 每页数量（默认 10）
     */
    private Integer pageSize = 10;

    /**
     * 排序方式：1-正序，2-倒序
     */
    private Integer sortType = 1;
}