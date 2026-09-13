package com.quanta.demo0.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 管理端 - 评论审核入参
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class CommentAuditDTO {

    /** 评论 ID */
    private Long commentId;

    /** 审核结果：1-通过 2-驳回 */
    private Integer auditResult;

    /** 驳回说明（可选，仅驳回时填写） */
    private String rejectReason;
}
