package com.quanta.demo0.result;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * ES 分页结果（只包含 ID 列表和总数）
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EsPageResult {

    /**
     * 内容 ID 列表（已按 ES 排序规则排好序）
     */
    private List<Long> ids;

    /**
     * 总条数（用于前端分页显示）
     */
    private Long total;
}