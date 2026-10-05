package com.quanta.demo0.feed.service;

import com.quanta.demo0.feed.dto.RecommendVisitor;

import java.util.Collection;
import java.util.Set;

/**
 * 推荐真实可视曝光服务。
 *
 * <p>曝光只由客户端进入视口后回传产生，与推荐 GET 下发解耦；实现负责校验页面归属
 * 并按主体共享逐条失效窗口。</p>
 */
public interface RecommendExposureService {

    /**
     * 查询指定 ID 中仍处于曝光窗口的帖子。
     *
     * @param visitor 可信推荐主体
     * @param contentIds 待查询帖子 ID
     * @return 仍在 24 小时窗口内的 ID
     */
    Set<Long> findExposed(RecommendVisitor visitor, Collection<Long> contentIds);

    /**
     * 记录真实可视帖子；重复回传不会续期，且 ID 必须属于该会话已下发页面。
     *
     * @param visitor 可信推荐主体
     * @param feedSessionId 推荐会话 ID
     * @param contentIds 进入视口的帖子 ID 批次
     */
    void record(RecommendVisitor visitor, String feedSessionId, Collection<Long> contentIds);
}
