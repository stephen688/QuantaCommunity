package com.quanta.demo0.vo;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 内容视图对象
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class ContentVO implements Serializable {

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
     * 发布时间
     */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")// 时区设置为东八区
    private LocalDateTime createTime;

    /**
     * 图片列表
     */
    private List<String> images;

    /**
     * 用户头像
     */
    private String avatarUrl;

    /**
     * 用户昵称
     */
    private String nickName;

    /**
     * 用户部门/专业
     */
    private String quantaDepartment;

    /**
     * 用户届数
     */
    private String quantaBatch;
    /**
     * 点赞数
     */
    private Integer liked;


    /**
     * 点赞高亮
     */    private Boolean isLiked;

    /**
     * 收藏状态
     */
    private Boolean isCollected;

    /**
     * 评论数量
     */
        private Integer commentCount;

    /**
     * 收藏数量
     */
    private Integer collectCount;

}
