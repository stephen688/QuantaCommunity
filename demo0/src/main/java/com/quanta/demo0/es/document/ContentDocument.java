package com.quanta.demo0.es.document;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * ES 内容文档实体类
 * 对应 MySQL 的 tb_content 表
 *
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class ContentDocument implements Serializable {

    /**
     * 内容唯一 ID（主键）
     */
    private Long contentId;

    /**
     * 内容类型：1-生活求助 2-专业问答
     */
    private Integer contentType;

    /**
     * 问题标题
     */
    private String title;

    /**
     * 问题描述
     */
    private String content;

    /**
     * 发布用户 ID
     */
    private Long publishUserId;

    /**
     * 审核状态：0-待审核 1-已通过 2-已驳回
     */
    private Integer auditStatus;

    /**
     * 点赞数
     */
    private Integer liked;

    /**
     * 收藏数
     */
    private Integer collectCount;

    /**
     * 评论数
     */
    private Integer commentCount;

    /**
     * 发布时间
     */
    private LocalDateTime createTime;

    /**
     * 软删除标识：0-未删除 1-已删除
     */
    private Integer isDeleted;
}