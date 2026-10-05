package com.quanta.demo0.identity.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 管理端审核结论的入参（POST /admin/identityExam/audit，{@code IdentityExamServiceImpl#audit}）。
 *
 * 【坑】auditResult 只接受 AuditStatus 里的 1（已通过）/ 2（已驳回）两个值，
 * 传其他值会被 audit() 抛"审核状态只能为…"；字段名是 auditResult 而不是
 * auditStatus，但码值用的是同一套 AuditStatus。
 */
@AllArgsConstructor
@Data
@NoArgsConstructor
@Builder
public class IdentityAuditDTO {

    /**
     * 审核id
     */
    // 注意是 tb_user_auth 的主键 authId，不是 userId——审核以"认证记录"为单位
    private Long authId;
    /**
     * 审核结果
     */
    // 1=通过 2=驳回；没有"待审核"这个合法入参，置回待审核只能等用户重新提交
    private Integer auditResult;
        /**
        * 驳回理由（仅在审核不通过时提供）
        */
    // 驳回时会写库并拼进通知文案；通过分支完全忽略该字段（不写库）
    private String auditRemark;
}
