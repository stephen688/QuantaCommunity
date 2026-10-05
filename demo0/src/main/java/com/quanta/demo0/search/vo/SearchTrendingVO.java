package com.quanta.demo0.search.vo;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * 搜索热门发现聚合 VO
 *
 * <p>GET /search/trending 的一次性返回体：把热词 / 热问题 / 热校友三张榜
 * 装进一个对象，前端一次请求刷完发现页。数据由 TrendingCacheService 两级缓存承载
 * （L1 Caffeine / L2 Redis），miss 时由 TrendingDataLoader.load 组装，
 * 空榜兜底默认热词（DEFAULT_HOT_KEYWORDS）。</p>
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class SearchTrendingVO implements Serializable {

    /**
     * 热门关键词列表
     *
     * <p>口径：全站搜索历史按关键词去重计数（SearchMapper.selectHotKeywords），
     * 数量上限来自配置 quanta.search.trending.keyword-limit（默认 10）。</p>
     */
    @Builder.Default
    private List<String> hotKeywords = new ArrayList<>();

    /**
     * 热门问题列表
     *
     * <p>口径：Redis 点赞排行 ZSET（RECOMMEND_HOT_ALL_KEY）+ MySQL TopN 兜底，
     * 仅保留已审核且未删除的内容。</p>
     */
    @Builder.Default
    private List<HotQuestionVO> hotQuestions = new ArrayList<>();

    /**
     * 热门校友列表
     *
     * <p>口径：Redis 粉丝排行 ZSET（USER_FOLLOWER_RANK_KEY）+ MySQL TopN 兜底，
     * 过滤账号状态不可用的用户。</p>
     */
    @Builder.Default
    private List<HotAlumniVO> hotAlumni = new ArrayList<>();
}