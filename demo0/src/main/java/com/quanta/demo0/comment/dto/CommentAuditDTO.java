package com.quanta.demo0.comment.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 管理端 - 评论审核入参
 *
 * 【谁在调用 / 谁不能伪造】服务 POST /admin/comment/audit，需要
 * CONTENT_AUDIT 权限点。审核人身份不在这里：auditComment 用
 * BaseContext.getCurrentId() 取管理员 ID，请求体只携带"审谁、怎么审"。
 * 【状态机入参】auditResult 只有 1/2 两个合法值，配合评论当前状态推出
 * 四条合法流转（待审→通过/驳回、已通过→驳回、已驳回→重新通过），
 * 非法组合在服务端直接 400，不是"传什么就改成什么"。
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
    // 驳回原因随状态一起写进 reject_reason 列，供用户端展示驳回理由；
    // 通过时传了也会被忽略（approveComment 传 null 覆盖）。
    private String rejectReason;
}
