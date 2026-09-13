package com.quanta.demo0.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 问题回答实体类
 * 对应数据库表：tb_question_answer
 * 用于专业问答区的回答功能
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class QuestionAnswer implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 回答唯一 ID（主键）
     */
    private Long answerId;

    /**
     * 所属问题 ID（关联 tb_content.content_id）
     */
    private Long questionId;

    /**
     * 回答发布用户 ID（关联 tb_user.user_id）
     */
    private Long userId;

    /**
     * 回答正文（支持长文本）
     * 与生活区短评论区分
     */
    private String content;

    /**
     * 回答点赞总数
     * 冗余字段，快速展示
     */
    private Integer likeCount;

    /**
     * 回答下的评论总数
     * 冗余字段
     */
    private Integer commentCount;

    /**
     * 是否被题主采纳
     * 0-未采纳 1-已采纳（知乎模式核心）
     */
    private Integer isAccepted;

    /**
     * 审核状态
     * 0-待审核 1-已通过（默认）2-已驳回
     */
    private Integer auditStatus;

    /**
     * 审核驳回原因
     * 审核驳回时填写
     */
    private String rejectReason;

    /**
     * 软删除标识
     * 0-未删除 1-已删除
     */
    private Integer isDeleted;

    /**
     * 回答发布时间
     */
    private LocalDateTime createTime;

    /**
     * 回答更新时间
     */
    private LocalDateTime updateTime;

}