package com.quanta.demo0.search.result;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * ES 分页结果（只包含 ID 列表和总数）
 *
 * ============================================================
 * 【为什么只装 ID + total？】
 * ============================================================
 * 设计意图是让 ES 只做"召回与排序"（返回主键 + 命中总数），
 * 业务字段由调用方回 MySQL 按主键批量取，避免 ES 与 MySQL 双份全字段
 * 互相追赶同步。当前代码库中内容检索实际走
 * ContentIndexService.searchContent（直接返回 ContentDocument 列表，
 * 全字段来自 ES 快照），本类暂无生产调用方，作为"瘦 ES 结果"契约预留。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EsPageResult {

    /**
     * 内容 ID 列表（已按 ES 排序规则排好序）
     *
     * <p>调用方按此顺序回查展示即可，不要自行重排——相关性/时间序是 ES 决定的。</p>
     */
    private List<Long> ids;

    /**
     * 总条数（用于前端分页显示）
     *
     * <p>来自 ES 命中总数；注意深分页受 from+size ≤ 10000 限制
     * （ContentIndexServiceImpl 里按 10000/pageSize 折算最大页码，超限直接空页）。</p>
     */
    private Long total;
}