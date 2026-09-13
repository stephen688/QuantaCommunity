package com.quanta.demo0.dto;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 管理端 - 内容审核入参
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class ContentAuditDTO {
    /**
     * 内容 ID
     */
    private Long contentId;

    /**
     * 回答 ID（用于回答审核场景）
     */
    private Long answerId;

    /**
     * 审核结果：1-通过 2-驳回
     */
    private Integer auditResult;

    /**
     * 驳回说明（可选，仅驳回时填写）
     */
    private String rejectReason;

    /**
     * 回答审核场景下的目标 ID：
     * 优先使用 answerId；为兼容历史请求，若 answerId 为空则回退 contentId。
     */
    public Long resolveAnswerId() {
        return answerId != null ? answerId : contentId;
    }
}