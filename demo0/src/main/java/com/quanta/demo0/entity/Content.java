package com.quanta.demo0.entity;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 内容实体类
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class Content implements Serializable {

    /**
     * 内容唯一 ID（主键）
     */
    private Long contentId;

    /**
     * 内容类型：1-生活求助 2-专业问答
     */
    private Integer contentType;

    /**
     * 问题标题（限制 50 字以内）
     */
    private String title;

    /**
     * 问题描述（限制 500 字以内）
     */
    private String content;

    /**
     * 发布用户 ID（关联 tb_user.user_id）
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
     * 点赞高亮
     */
    private Boolean isLiked;

    /**
     * 收藏状态
     */
    private Boolean isCollected;

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
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createTime;

    /**
     * 更新时间
     */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime updateTime;

    /**
     * 软删除标识：0-未删除 1-已删除
     */
    private Integer isDeleted;

    // 添加：
    /**
     * ES 检索得分（仅用于检索，不落库、不序列化）
     */
   @JsonIgnore
    private Double esSearchScore;
}
