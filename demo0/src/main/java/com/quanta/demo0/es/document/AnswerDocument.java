package com.quanta.demo0.es.document;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * ES 回答文档实体类
 * 对应 MySQL 的 tb_question_answer 表
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class AnswerDocument implements Serializable {

    /**
     * 回答唯一 ID（主键）
     */
    private Long answerId;

    /**
     * 所属问题 ID（关联 tb_content.content_id）
     */
    private Long questionId;

    /**
     * 问题标题（冗余字段，便于 BM25 检索）
     */
    private String questionTitle;

    /**
     * 回答正文
     */
    private String answerContent;

    /**
     * 回答发布用户 ID
     */
    private Long userId;

    /**
     * 审核状态：0-待审核 1-已通过 2-已驳回
     */
    private Integer auditStatus;

    /**
     * 点赞数
     */
    private Integer likeCount;
    /**
     * ES 检索得分（用于 RAG 融合排序）
     */
    private Double esSearchScore;

    /**
     * 评论数
     */
    private Integer commentCount;

    /**
     * 是否被采纳：0-未采纳 1-已采纳
     */
    private Integer isAccepted;

    /**
     * 发布时间
     */
    private LocalDateTime createTime;

    /**
     * 软删除标识：0-未删除 1-已删除
     */
    private Integer isDeleted;
}