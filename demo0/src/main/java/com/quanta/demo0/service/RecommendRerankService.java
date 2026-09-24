package com.quanta.demo0.service;

import com.quanta.demo0.entity.Content;

import java.util.List;

/**
 * 画像流重排服务（推荐流个性化 03 Task 3.2，D6/D7）。
 * 职责：固定召回窗口（hot 池 top N ∪ latest 池 top M）→ 曝光过滤 → MySQL 可见性兜底 →
 * 归一化算分（α·normHot + (1-α)·matchScore）→ finalScore 排序截页 → 回写曝光（隐式游标）。
 * 边界：匿名用户走 α=1 纯热度（不读不写曝光、不读画像）；不读 ZSET score
 * （latest 池 score 是时间戳、hot 池是热度分，两池语义不同，排序分一律 Java 现算）；
 * 只返回 Content 实体列表，VO 装配（作者/高亮）由 recommend() 现有步骤承担。
 */
public interface RecommendRerankService {

    /**
     * 画像流重排：召回 → 曝光过滤 → 归一化算分 → 排序截页 → 回写曝光。
     * 匿名用户走 α=1 纯热度（不读不写曝光）。游标入参忽略（曝光 set 为隐式游标）。
     *
     * @param userId      当前用户 ID（匿名传 null）
     * @param contentType 内容类型（null=全量池，1=生活池，2=专业池；分类 tab 透传）
     * @param pageSize    本页条数（由 recommend() 归一化为正数后传入）
     * @return RerankResult：contents 为当页内容（保持推荐序）；hasMore = 过滤曝光与不可见帖后候选是否还有剩余
     */
    RerankResult rerank(Long userId, Integer contentType, int pageSize);

    /**
     * 画像流重排结果。
     *
     * @param contents 当页内容列表（finalScore 降序，同分 contentId 降序）
     * @param hasMore  过滤曝光与不可见帖后的候选数是否大于 pageSize（池子耗尽为 false）
     */
    record RerankResult(List<Content> contents, boolean hasMore) {
    }
}
