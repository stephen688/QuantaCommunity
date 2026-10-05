package com.quanta.demo0.search.service.impl;

import com.quanta.demo0.platform.redis.constant.RedisConstants;
import com.quanta.demo0.content.vo.ContentSnapshotVO;
import com.quanta.demo0.user.vo.UserAuthInfoVO;
import com.quanta.demo0.content.service.ContentQueryService;
import com.quanta.demo0.search.mapper.SearchMapper;
import com.quanta.demo0.user.service.UserQueryService;
import com.quanta.demo0.search.properties.SearchTrendingProperties;
import com.quanta.demo0.search.vo.HotAlumniVO;
import com.quanta.demo0.search.vo.HotQuestionVO;
import com.quanta.demo0.search.vo.SearchTrendingVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 热榜聚合数据构建器。
 *
 * <p>MySQL 是业务事实源，Redis ZSET 只提供优先顺序。批量查询返回顺序
 * 不作为业务排序依据，最终顺序始终按 ZSET member 顺序恢复。</p>
 *
 * <p>本类是热榜读链路的最底层：上层 SearchServiceImpl#getTrending 先经
 * TrendingCacheService（L1/L2 缓存），缓存未命中才调用 {@link #load()} 回源。</p>
 *
 * ============================================================
 * 【三大榜单的数据分别从哪来？】
 * ============================================================
 * 热门关键词：直接查 MySQL 用户搜索历史表 tb_user_search_history
 * （SearchMapper.selectHotKeywords：GROUP BY keyword 后按 COUNT(*) 降序、
 * 最近搜索时间降序取前 limit 个）——搜的人越多越热。
 *
 * 热门问题：读 Redis ZSET content:recommend:hot:all（member=contentId，
 * score=热度分），热度分由 feed 域 HotScoreCalculator 计算：
 * **热度分 = (点赞×3 + 评论×2 + 收藏×5) / (发布小时数+2)^1.5，
 * 基础分为 0 时改用 20 分保底再除以时间衰减**；本类只消费，不重复实现公式。
 *
 * 热门校友：读 Redis ZSET user:follower:rank（member=userId，
 * score=粉丝数），由 follow 域在关注/取关时 ZINCRBY ±1 维护。
 *
 * ============================================================
 * 【为什么顺序以 ZSET 为准，详情却要回 MySQL 批量查？】
 * ============================================================
 * ZSET 只保证名次，不保证成员当前仍可见——里面可能残留已删除、
 * 未过审的 contentId 或被封禁的 userId；而批量 IN 查询的返回顺序
 * 也不可靠。所以流程是：取 ZSET 顺序 → 按 id 批量查明细 → 过滤无效项
 * → 用 Set 去重 → **严格沿 ZSET member 迭代顺序恢复名次**。
 * 榜单不足 limit 时再用 MySQL TopN 兜底补齐，保证搜索页不缺位。
 */
@Component
@Slf4j
public class TrendingDataLoader {

    private static final List<String> DEFAULT_HOT_KEYWORDS = List.of(
            "Spring Boot",
            "考研复习",
            "露营",
            "校友聚会",
            "小程序分包"
    );

    private final SearchMapper searchMapper;
    private final StringRedisTemplate stringRedisTemplate;
    private final SearchTrendingProperties searchTrendingProperties;
    private final ContentQueryService contentQueryService;
    private final UserQueryService userQueryService;

    public TrendingDataLoader(
            SearchMapper searchMapper,
            StringRedisTemplate stringRedisTemplate,
            SearchTrendingProperties searchTrendingProperties,
            ContentQueryService contentQueryService,
            UserQueryService userQueryService
    ) {
        this.searchMapper = searchMapper;
        this.stringRedisTemplate = stringRedisTemplate;
        this.searchTrendingProperties = searchTrendingProperties;
        this.contentQueryService = contentQueryService;
        this.userQueryService = userQueryService;
    }

    /**
     * 聚合三大榜单（热门关键词 / 热门问题 / 热门校友）。
     *
     * 【坑】本方法完全不带缓存，每次调用都是真实的 Redis + MySQL 查询；
     * 必须由上层先过 TrendingCacheService 两级缓存再进来。
     * 各榜单条数来自 quanta.search.trending 配置（keyword/question/alumni-limit，
     * 默认 10），null 或负数会被 {@link #limitOf(int)} 归一成 0，不抛异常。
     */
    public SearchTrendingVO load() {
        int keywordLimit = limitOf(searchTrendingProperties.getKeywordLimit());
        int questionLimit = limitOf(searchTrendingProperties.getQuestionLimit());
        int alumniLimit = limitOf(searchTrendingProperties.getAlumniLimit());

        SearchTrendingVO result = new SearchTrendingVO();
        result.setHotKeywords(loadKeywords(keywordLimit));
        result.setHotQuestions(loadQuestions(questionLimit));
        result.setHotAlumni(loadAlumni(alumniLimit));
        return result;
    }

    /**
     * 热门关键词：直接以 MySQL 为唯一数据源（这是三大榜单里唯一不读 ZSET 的）。
     * SQL 按"搜索次数降序 + 最近搜索时间降序"排名，天然反映当前搜索热度。
     *
     * 【边界】搜索历史为空（新库 / 被用户清空）时回退到写死的
     * {@link #DEFAULT_HOT_KEYWORDS}，保证前端搜索页永远有词可展示；
     * SQL 里已经 LIMIT，这里再 stream().limit(limit) 是双保险，
     * 防止 mapper 与配置将来不一致时超发。
     */
    private List<String> loadKeywords(int limit) {
        List<String> keywords = searchMapper.selectHotKeywords(limit);
        // 空结果兜底：最多取 limit 个默认词，避免 limit 小于默认词数量时给多。
        if (keywords == null || keywords.isEmpty()) {
            return new ArrayList<>(DEFAULT_HOT_KEYWORDS.subList(
                    0,
                    Math.min(limit, DEFAULT_HOT_KEYWORDS.size())
            ));
        }
        return keywords.stream()
                .limit(limit)
                .toList();
    }

    /**
     * 热门问题：两段式取数——Redis 榜单给"谁热"，MySQL 给"详情与准入"。
     *
     * 1. 读 ZSET content:recommend:hot:all 的前 limit×2 个 contentId；
     * 2. 按 id 批量查内容快照（getContentFactSnapshots），过滤已删除/未过审项，
     *    并严格沿 ZSET 顺序恢复名次；
     * 3. 不足 limit 时用 getTopLikedContentSnapshots（MySQL 点赞 TopN）兜底补齐。
     *
     * 【为什么绕这一圈？】直接信 ZSET 会把删帖、未过审内容展示进榜单；
     * 直接查 MySQL 又拿不到热度名次（热度分需要全量互动数据才能算）。
     * 两边各取所长，MySQL 始终是最终准入的裁判。
     */
    private List<HotQuestionVO> loadQuestions(int limit) {
        if (limit == 0) {
            return Collections.emptyList();
        }

        List<Long> rankedIds = readRankedIds(
                RedisConstants.RECOMMEND_HOT_ALL_KEY,
                limit
        );
        List<ContentSnapshotVO> rankedContents = rankedIds.isEmpty()
                ? Collections.emptyList()
                : contentQueryService.getContentFactSnapshots(rankedIds);
        Map<Long, ContentSnapshotVO> contentsById = indexContents(rankedContents);

        List<HotQuestionVO> questions = new ArrayList<>(limit);
        Set<Long> selectedIds = new HashSet<>();
        for (Long rankedId : rankedIds) {
            ContentSnapshotVO content = contentsById.get(rankedId);
            if (isApprovedContent(content) && selectedIds.add(rankedId)) {
                questions.add(toHotQuestion(content));
                if (questions.size() == limit) {
                    return questions;
                }
            }
        }

        // 查询完整 limit，避免 DB TopN 前几项与排行结果重复时补不满。
        List<ContentSnapshotVO> fallbackContents = contentQueryService.getTopLikedContentSnapshots(limit);
        if (fallbackContents == null) {
            return questions;
        }
        for (ContentSnapshotVO content : fallbackContents) {
            if (isApprovedContent(content)
                    && selectedIds.add(content.getContentId())) {
                questions.add(toHotQuestion(content));
                if (questions.size() == limit) {
                    break;
                }
            }
        }
        return questions;
    }

    /**
     * 热门校友：与 {@link #loadQuestions(int)} 同构的两段式取数。
     *
     * 1. 读 ZSET user:follower:rank 的前 limit×2 个 userId（score=粉丝数）；
     * 2. 批量查用户认证信息（getUserAuthInfos），过滤不可用账号后沿 ZSET 顺序恢复名次；
     * 3. 不足 limit 时用 getTopFollowedUsers（MySQL 粉丝数 TopN）兜底。
     */
    private List<HotAlumniVO> loadAlumni(int limit) {
        if (limit == 0) {
            return Collections.emptyList();
        }

        List<Long> rankedIds = readRankedIds(
                RedisConstants.USER_FOLLOWER_RANK_KEY,
                limit
        );
        List<UserAuthInfoVO> rankedUsers = rankedIds.isEmpty()
                ? Collections.emptyList()
                : userQueryService.getUserAuthInfos(rankedIds);
        Map<Long, UserAuthInfoVO> usersById = indexUsers(rankedUsers);

        List<HotAlumniVO> alumni = new ArrayList<>(limit);
        Set<Long> selectedIds = new HashSet<>();
        for (Long rankedId : rankedIds) {
            UserAuthInfoVO user = usersById.get(rankedId);
            if (isAvailableAlumni(user) && selectedIds.add(rankedId)) {
                alumni.add(toHotAlumni(user));
                if (alumni.size() == limit) {
                    return alumni;
                }
            }
        }

        // 查询完整 limit，避免 DB TopN 前几项与排行结果重复时补不满。
        List<UserAuthInfoVO> fallbackUsers = userQueryService.getTopFollowedUsers(limit);
        if (fallbackUsers == null) {
            return alumni;
        }
        for (UserAuthInfoVO user : fallbackUsers) {
            if (isAvailableAlumni(user)
                    && selectedIds.add(user.getUserId())) {
                alumni.add(toHotAlumni(user));
                if (alumni.size() == limit) {
                    break;
                }
            }
        }
        return alumni;
    }

    /**
     * 读榜单 ZSET 的前 limit×2 个 member（ZREVRANGE，score 从大到小）并转成 Long。
     *
     * 【坑 1】这里取 2 倍是刻意的过采样：ZSET 里可能混有已删除/未过审成员，
     * 上层过滤后仍要凑满 limit 个，多取一倍留余量，减少走 MySQL 兜底的次数。
     * 【坑 2】Redis 读失败不抛出，而是返回空列表让上层走 MySQL 兜底——
     * 热榜是展示型功能，Redis 故障不应导致整个搜索页 500（降级而非报错）。
     * member 非数字只记 warn 跳过，不让单个脏数据拖垮整个榜单。
     */
    private List<Long> readRankedIds(String key, int limit) {
        try {
            Set<String> members = stringRedisTemplate.opsForZSet()
                    .reverseRange(key, 0, (long) limit * 2 - 1);
            if (members == null || members.isEmpty()) {
                return Collections.emptyList();
            }

            Set<Long> ids = new LinkedHashSet<>();
            for (String member : members) {
                if (member == null) {
                    log.warn("热榜排行 member 为空，跳过，key={}", key);
                    continue;
                }
                try {
                    ids.add(Long.valueOf(member));
                } catch (NumberFormatException exception) {
                    log.warn(
                            "热榜排行 member 不是合法 Long，跳过，key={}, member={}",
                            key,
                            member
                    );
                }
            }
            return new ArrayList<>(ids);
        } catch (RuntimeException exception) {
            log.warn(
                    "读取热榜 Redis 排行失败，回退 MySQL，key={}, stage=ranking-read",
                    key,
                    exception
            );
            return Collections.emptyList();
        }
    }

    private Map<Long, ContentSnapshotVO> indexContents(List<ContentSnapshotVO> contents) {
        Map<Long, ContentSnapshotVO> indexed = new HashMap<>();
        if (contents == null) {
            return indexed;
        }
        for (ContentSnapshotVO content : contents) {
            if (content != null && content.getContentId() != null) {
                indexed.putIfAbsent(content.getContentId(), content);
            }
        }
        return indexed;
    }

    private Map<Long, UserAuthInfoVO> indexUsers(List<UserAuthInfoVO> users) {
        Map<Long, UserAuthInfoVO> indexed = new HashMap<>();
        if (users == null) {
            return indexed;
        }
        for (UserAuthInfoVO user : users) {
            if (user != null && user.getUserId() != null) {
                indexed.putIfAbsent(user.getUserId(), user);
            }
        }
        return indexed;
    }

    /**
     * 内容准入门槛：未删除（isDeleted=0）且审核通过（auditStatus=1），
     * 与内容域的可见性口径保持一致，防止 ZSET 里的脏成员泄漏到榜单。
     */
    private boolean isApprovedContent(ContentSnapshotVO content) {
        return content != null
                && content.getContentId() != null
                && Integer.valueOf(0).equals(content.getIsDeleted())
                && Integer.valueOf(1).equals(content.getAuditStatus());
    }

    /**
     * 校友可用性门槛：账号状态为 null 视作正常（兼容历史数据），
     * 非 0（如封禁）一律不可上榜。
     */
    private boolean isAvailableAlumni(UserAuthInfoVO user) {
        Integer accountStatus = user == null ? null : user.getAccountStatus();
        return user != null
                && user.getUserId() != null
                && (accountStatus == null || accountStatus == 0);
    }

    private HotQuestionVO toHotQuestion(ContentSnapshotVO content) {
        return HotQuestionVO.builder()
                .contentId(content.getContentId())
                .title(content.getTitle())
                .liked(content.getLikedCount())
                .build();
    }

    private HotAlumniVO toHotAlumni(UserAuthInfoVO user) {
        return HotAlumniVO.builder()
                .userId(user.getUserId())
                .nickName(user.getNickName())
                .avatarUrl(user.getAvatarUrl())
                .build();
    }

    /** 配置防御：limit 为 null 或负数一律按 0 处理（0 = 不展示该榜单），绝不抛异常。 */
    private int limitOf(Integer limit) {
        return limit == null ? 0 : Math.max(limit, 0);
    }
}
