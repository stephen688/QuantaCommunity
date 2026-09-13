package com.quanta.demo0.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 评论举报 DTO
 *
 * 【用途】用于接收前端提交的评论举报数据
 *
 * 【字段说明】
 * - commentId: 被举报的评论 ID（必填）
 * - reportType: 举报类型（必填，1-垃圾广告 2-人身攻击 3-违规内容 4-虚假信息 5-其他）
 *
 * @author Quanta Team
 * @since 2026-05-01
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CommentReportDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 被举报的评论 ID（必填）
     */
    private Long commentId;

    /**
     * 举报类型（必填）
     * 1-垃圾广告/营销 2-人身攻击/辱骂 3-违规内容/色情暴力 4-虚假信息/造谣 5-其他
     */
    private Integer reportType;
}