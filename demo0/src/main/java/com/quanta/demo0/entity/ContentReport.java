package com.quanta.demo0.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 帖子举报实体类
 * 【对应表】tb_content_report
 * 【字段说明】
 * - id: 举报记录 ID（主键）
 * - contentId: 被举报的帖子 ID
 * - reporterId: 举报人用户 ID
 * - reportType: 举报类型（1-垃圾广告 2-人身攻击 3-违规内容 4-虚假信息 5-其他）
 * - status: 处理状态（0-待处理 1-处理中 2-已处理 3-已驳回）
 * - handlerId: 处理人管理员 ID
 * - handleResult: 处理结果（1-删除帖子 2-警告用户 3-删除 + 警告 4-驳回举报）
 * - handleRemark: 处理备注
 * - handleTime: 处理时间
 * - createTime: 举报时间
 * - updateTime: 更新时间
 * - isDeleted: 逻辑删除（0-未删除 1-已删除）
 *
 * @author Quanta Team
 * @since 2026-05-01
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ContentReport implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 举报记录 ID（主键）
     */
    private Long id;

    /**
     * 被举报的帖子 ID
     */
    private Long contentId;

    /**
     * 举报人用户 ID
     */
    private Long reporterId;

    /**
     * 举报类型：1-垃圾广告/营销 2-人身攻击/辱骂 3-违规内容/色情暴力 4-虚假信息/造谣 5-其他
     */
    private Integer reportType;

    /**
     * 处理状态：0-待处理 1-处理中 2-已处理（通过） 3-已驳回
     */
    private Integer status;

    /**
     * 处理人管理员 ID
     */
    private Long handlerId;

    /**
     * 处理结果：1-删除帖子 2-警告用户 3-删除帖子 + 警告用户 4-驳回举报
     */
    private Integer handleResult;

    /**
     * 处理备注（管理员补充说明）
     */
    private String handleRemark;

    /**
     * 处理时间
     */
    private LocalDateTime handleTime;

    /**
     * 举报时间
     */
    private LocalDateTime createTime;

    /**
     * 更新时间
     */
    private LocalDateTime updateTime;

    /**
     * 逻辑删除：0-未删除 1-已删除
     */
    private Integer isDeleted;
}