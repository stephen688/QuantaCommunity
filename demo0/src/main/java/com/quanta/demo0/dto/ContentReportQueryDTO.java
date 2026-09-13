package com.quanta.demo0.dto;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 管理端 - 帖子举报分页查询入参
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class ContentReportQueryDTO {
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
     * 处理状态筛选（可选）：0-待处理 1-处理中 2-已处理 3-已驳回
     */
    private Integer status;
}