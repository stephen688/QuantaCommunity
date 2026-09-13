 package com.quanta.demo0.dto;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 管理端 - 内容分页查询入参
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class ContentAdminQueryDTO {
    /**
     * 页码，默认 1
     */
    @Builder.Default
    private Integer pageNum = 1;

    /**
     * 每页数量，默认 10
     */
    @Builder.Default
    private Integer pageSize = 10;

    /**
     * 审核状态筛选（可选）：0-待审核 1-已通过 2-已驳回
     */
    private Integer auditStatus;

    /**
     * 内容类型筛选（可选）：1-生活求助 2-专业问答
     */
    private Integer contentType;
}