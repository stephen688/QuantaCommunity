package com.quanta.demo0.search.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 内容搜索请求参数（GET /search/content 的查询串绑定对象）。
 *
 * ============================================================
 * 【sortType 为什么目前是"摆设"？】
 * ============================================================
 * 字段约定 new-按时间 / hot-按热度，但检索链路
 * （ContentIndexServiceImpl.searchContent → ElasticsearchQueryFactory.contentSearch）
 * 并没有消费它：ES 固定先按 _score 降序、再按 createTime 降序排序，
 * searchContent 的入参里也压根没有排序维度。**读代码时不要以为传 hot 就会改变排序。**
 * 保留字段是为了前端契约先定型；真要支持切换，得在 ES 查询工厂里
 * 加按字段排序 / function_score 的分支。
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class SearchDTO implements Serializable {
    /**
     * 搜索关键词（必填）
     *
     * <p>Service 侧校验：非空、trim 后不超过 50 字（ContentSearchServiceImpl）。</p>
     */
    private String keyword;

    /**
     * 内容类型（可选）：1-生活求助 2-专业问答 null-全部
     *
     * <p>用 Integer 而不是枚举：它会被原样传给 ES 的 term filter
     * （termQuery("contentType", ...)），与 ES 索引里的 integer 字段直接对应。</p>
     */
    private Integer contentType;

    /**
     * 排序方式（可选）：new-按时间 hot-按热度 默认 new
     *
     * <p>【注意】当前实现未消费该字段，实际排序固定为相关性优先（见类注释）。</p>
     */
    @Builder.Default
    private String sortType = "new";

    /**
     * 当前页码（默认 1）
     *
     * <p>HTTP 绑定不经过 Builder，@Builder.Default 不生效；缺省/非法值由
     * Service 层兜底为 1。深分页受 ES from+size ≤ 10000 约束，超限页码直接返回空页。</p>
     */
    @Builder.Default
    private Integer current = 1;

    /**
     * 每页条数（默认 10）
     *
     * <p>同上，HTTP 缺省时由 Service 层兜底为 10。</p>
     */
    @Builder.Default
    private Integer pageSize = 10;
}