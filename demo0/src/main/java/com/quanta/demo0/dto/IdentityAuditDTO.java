package com.quanta.demo0.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@AllArgsConstructor
@Data
@NoArgsConstructor
@Builder
public class IdentityAuditDTO {

    /**
     * 审核id
     */
    private Long authId;
    /**
     * 审核结果
     */
    private Integer auditResult;
        /**
        * 驳回理由（仅在审核不通过时提供）
        */
    private String auditRemark;
}
