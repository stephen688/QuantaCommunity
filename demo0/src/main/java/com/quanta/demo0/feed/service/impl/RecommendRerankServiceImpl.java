package com.quanta.demo0.feed.service.impl;

import com.quanta.demo0.platform.redis.constant.RedisConstants;
import com.quanta.demo0.content.entity.Content;
import com.quanta.demo0.platform.common.enums.AuditStatus;
import com.quanta.demo0.content.exception.ContentFailedException;
import com.quanta.demo0.mapper.ContentMapper;
import com.quanta.demo0.feed.properties.RecommendProperties;
import com.quanta.demo0.feed.service.RecommendRerankService;
import com.quanta.demo0.service.UserProfileService;
import com.quanta.demo0.feed.utils.HotScoreCalculator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * 画像流重排服务实现（推荐流个性化 03 Task 3.2，D6/D7）。
 * 核心流程：召回（hot∪latest 双池 ZREVRANGE top N，contentId 去重、不读 score）→
 * 曝光过滤（仅登录用户，recommend:exposed:{userId} 为隐式游标）→ MySQL 可见性兜底 →
 * 读画像 → finalScore = α·normHot + (1-α)·matchScore + explicitScore 算分排序 → 截页回写曝光。
 * 边界：匿名 α=1 纯热度、不读不写曝光；归一化在候选集内做 min-max（最热=1.0，单候选=1.0）；
 * 显式偏好独立有符号加分（④）：不参与行为分母，负偏好只降权不剔除；
 * 曝光读写语义从 ContentServiceImpl 既有逻辑迁移（key / TTL 24h / 超 1000 pop 100 逐字保留）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RecommendRerankServiceImpl implements RecommendRerankService {

    private final StringRedisTemplate stringRedisTemplate;
    private final ContentMapper contentMapper;
    private final UserProfileService userProfileService;
    private final RecommendProperties recommendProperties;

    /**
     * 画像流重排主流程（步骤行内注释对应计划 03 Task 3.2 的 8 步固定流程）。
     */
    @Override
    public RerankResult rerank(Long userId, Integer contentType, int pageSize) {
        RecommendProperties.Profile profileConfig = recommendProperties.getProfile();

        // ========== 步骤 1：召回——hot 池 top recallHotSize + latest 池 top recallLatestSize ==========
        // 双池按 contentId 去重合并（hot 优先保留）；不读 ZSET score（两池 score 语义不同，总览 §2.9）
        List<Long> recallIds = recallCandidateIds(
                contentType, profileConfig.getRecallHotSize(), profileConfig.getRecallLatestSize());
        if (recallIds.isEmpty()) {
            // 池子耗尽：零候选短路返回，不触发后续 DB/画像/曝光
            return new RerankResult(new ArrayList<>(), false);
        }

        // ========== 步骤 2：曝光过滤（仅登录用户；匿名跳过，不读曝光） ==========
        Set<String> exposedSet = userId == null ? Collections.emptySet() : loadExposedContentIds(userId);
        List<Long> unexposedIds = new ArrayList<>();
        for (Long contentId : recallIds) {
            if (!exposedSet.contains(contentId.toString())) {
                unexposedIds.add(contentId);
            }
        }

        // ========== 步骤 3：查帖子并过滤非审核通过/已删（MySQL 是事实源，兜底 ZSET 时差） ==========
        List<Content> candidates = loadVisibleContents(unexposedIds);
        if (candidates.isEmpty()) {
            return new RerankResult(new ArrayList<>(), false);
        }

        // ========== 步骤 4：读画像（匿名/无画像返回空 Map → S=0 → α=1 纯热度） ==========
        Map<String, Double> profile = userId == null
                ? Collections.emptyMap()
                : userProfileService.getProfile(userId);

        // ========== 步骤 5：算分——行为画像基础分 + 独立显式偏好项 ==========
        Map<String, Double> explicitProfile = userId == null
                ? Collections.emptyMap() : userProfileService.getExplicitProfile(userId);
        List<Content> ranked = scoreAndSort(candidates, profile, explicitProfile, profileConfig);

        // ========== 步骤 6：排序截断（finalScore 降序已在 scoreAndSort 完成） ==========
        boolean hasMore = ranked.size() > pageSize;
        List<Content> pageContents = hasMore
                ? new ArrayList<>(ranked.subList(0, pageSize))
                : ranked;

        // ========== 步骤 7：回写曝光（仅登录用户；本页内容记入曝光 set 作隐式游标） ==========
        if (userId != null && !pageContents.isEmpty()) {
            saveExposedSet(userId, pageContents);
        }

        // ========== 步骤 8：hasMore = 过滤曝光与不可见帖后的候选数 > pageSize ==========
        return new RerankResult(pageContents, hasMore);
    }

    /**
     * 双池召回：hot 池与 latest 池各取 top N，按 contentId 去重合并（hot 池优先保留）。
     * 池名复用既有 key 规则（contentType 分类池保持，小程序分类 tab 可用）。
     */
    private List<Long> recallCandidateIds(Integer contentType, int recallHotSize, int recallLatestSize) {
        LinkedHashSet<Long> recallIds = new LinkedHashSet<>();
        // hot 池先入集合：两池同帖时 hot 优先保留（去重语义）
        collectZSetTopIds(resolveRecommendHotKey(contentType), recallHotSize, recallIds);
        collectZSetTopIds(resolveRecommendKey(contentType), recallLatestSize, recallIds);
        return new ArrayList<>(recallIds);
    }

    /**
     * 从 ZSET 拉取 top N 个 contentId（ZREVRANGE，只取 member 不读 score）。
     * 脏数据（非数字 member）跳过并告警，不中断整轮召回。
     */
    private void collectZSetTopIds(String key, int limit, LinkedHashSet<Long> target) {
        if (limit <= 0) {
            return; // 配置校验已保证 ≥1，此处防御非法注入
        }
        Set<String> ids = stringRedisTemplate.opsForZSet().reverseRange(key, 0, limit - 1);
        if (ids == null) {
            return;
        }
        for (String id : ids) {
            try {
                target.add(Long.valueOf(id));
            } catch (NumberFormatException e) {
                log.warn("画像流召回跳过 Redis ZSET 脏数据，key={}, member={}", key, id);
            }
        }
    }

    /**
     * 批量查帖并只保留审核通过且未删除的内容（MySQL 是事实源，兜底 ZSET 与数据库的时差）。
     */
    private List<Content> loadVisibleContents(List<Long> contentIds) {
        if (contentIds.isEmpty()) {
            return new ArrayList<>();
        }
        List<Content> contents = contentMapper.selectBatchIds(contentIds);
        if (contents == null) {
            return new ArrayList<>();
        }
        List<Content> visibleContents = new ArrayList<>();
        for (Content content : contents) {
            if (content == null || content.getContentId() == null) {
                continue; // 防御脏数据
            }
            if (AuditStatus.APPROVED.getCode().equals(content.getAuditStatus())
                    && Integer.valueOf(0).equals(content.getIsDeleted())) {
                visibleContents.add(content);
            }
        }
        return visibleContents;
    }

    /**
     * 算分与排序：候选集内 min-max 热度归一化 + 画像匹配分 + α 过渡，finalScore 降序。
     * 同分按 contentId 降序（稳定排序，与 hot 池 filterAndSort 的二级排序语义一致）。
     */
    private List<Content> scoreAndSort(
            List<Content> candidates, Map<String, Double> profile, Map<String, Double> explicitProfile,
            RecommendProperties.Profile profileConfig) {
        // 5.1 热度分现算（公式唯一真源 HotScoreCalculator，不读 ZSET score）
        Map<Long, Double> hotScores = new HashMap<>();
        double maxHotScore = Double.NEGATIVE_INFINITY;
        double minHotScore = Double.POSITIVE_INFINITY;
        for (Content content : candidates) {
            double hotScore = HotScoreCalculator.calculate(content);
            hotScores.put(content.getContentId(), hotScore);
            maxHotScore = Math.max(maxHotScore, hotScore);
            minHotScore = Math.min(minHotScore, hotScore);
        }

        // 5.2 α 过渡：S = min(1, __total/饱和阈值)（无画像 S=0 → α=1 纯热度兜底）
        double saturation = 0.0;
        Double totalWeight = profile.get(RedisConstants.USER_PROFILE_TOTAL_FIELD);
        if (totalWeight != null && profileConfig.getBehaviorSaturateThreshold() > 0) {
            saturation = Math.min(1.0, totalWeight / profileConfig.getBehaviorSaturateThreshold());
        }
        double alpha = 1.0 - saturation * (1.0 - profileConfig.getAlphaMin());

        // 5.3 画像标签总权重（不含 __total，__total 是 α 数据源不是兴趣标签）
        double profileTagWeightSum = 0.0;
        for (Map.Entry<String, Double> entry : profile.entrySet()) {
            if (!RedisConstants.USER_PROFILE_TOTAL_FIELD.equals(entry.getKey())) {
                profileTagWeightSum += entry.getValue();
            }
        }

        // 5.4 显式项独立加分：最强负偏好优先，避免多个正标签抵消明确厌恶。
        Map<Long, Double> finalScores = new HashMap<>();
        for (Content content : candidates) {
            double normHot = normalizeHotScore(
                    hotScores.get(content.getContentId()), minHotScore, maxHotScore);
            double matchScore = calculateMatchScore(content, profile, profileTagWeightSum);
            double explicitScore = calculateExplicitScore(content, explicitProfile);
            finalScores.put(content.getContentId(), alpha * normHot + (1.0 - alpha) * matchScore + explicitScore);
        }

        // 5.5 排序：finalScore 降序，同分 contentId 降序
        List<Content> ranked = new ArrayList<>(candidates);
        ranked.sort((left, right) -> {
            int byScore = Double.compare(
                    finalScores.get(right.getContentId()), finalScores.get(left.getContentId()));
            if (byScore != 0) {
                return byScore;
            }
            return Long.compare(right.getContentId(), left.getContentId());
        });
        return ranked;
    }

    /**
     * 候选集内 min-max 热度归一化：最热=1.0、最低=0；单候选（min==max）固定 1.0。
     */
    private double normalizeHotScore(double hotScore, double minHotScore, double maxHotScore) {
        if (maxHotScore <= minHotScore) {
            return 1.0; // 单候选或全同分：归一化分母为 0，按满格热度处理
        }
        return (hotScore - minHotScore) / (maxHotScore - minHotScore);
    }

    /** 同帖多个标签不叠加放大；有明确负反馈则用最强惩罚，否则取最大喜好增益。 */
    private double calculateExplicitScore(Content content, Map<String, Double> profile) {
        if (profile == null || profile.isEmpty()) {
            return 0.0;
        }
        double positive = 0.0;
        double negative = 0.0;
        for (String topic : userProfileService.resolveContentTags(content)) {
            double weight = profile.getOrDefault(topic, 0.0);
            positive = Math.max(positive, weight);
            negative = Math.min(negative, weight);
        }
        return negative < 0.0 ? negative : positive;
    }

    /**
     * 匹配分：帖子标签与画像 field 交集的权重和 ÷ 画像标签总权重（不含 __total），归一到 [0,1]。
     * 无画像或画像无标签权重时返回 0（新用户/兴趣全衰减用户，排序退化为热度）。
     */
    private double calculateMatchScore(Content content, Map<String, Double> profile, double profileTagWeightSum) {
        if (profile.isEmpty() || profileTagWeightSum <= 0) {
            return 0.0;
        }
        double matchedWeight = 0.0;
        for (String tag : userProfileService.resolveContentTags(content)) {
            Double tagWeight = profile.get(tag);
            if (tagWeight != null) {
                matchedWeight += tagWeight;
            }
        }
        return matchedWeight / profileTagWeightSum;
    }

    /**
     * 读取已曝光 contentId 集合（recommend:exposed:{userId}，隐式游标）。
     */
    private Set<String> loadExposedContentIds(Long userId) {
        String exposedKey = RedisConstants.RECOMMEND_EXPOSED_KEY_PREFIX + userId;
        Set<String> exposedContentIds = stringRedisTemplate.opsForSet().members(exposedKey);
        return exposedContentIds != null ? exposedContentIds : Collections.emptySet();
    }

    /**
     * 回写曝光 set（从 ContentServiceImpl 既有 saveExposedSet 迁移，语义逐字保留）：
     * 记录本页 contentId、TTL 24 小时、超 1000 条随机 pop 100（FIFO 近似）。
     */
    private void saveExposedSet(Long userId, List<Content> pageContents) {
        String exposedKey = RedisConstants.RECOMMEND_EXPOSED_KEY_PREFIX + userId;
        String[] contentIds = pageContents.stream()
                .map(content -> content.getContentId().toString())
                .toArray(String[]::new);
        // 1. 保存曝光记录，设置过期时间为 24 小时
        stringRedisTemplate.opsForSet().add(exposedKey, contentIds);
        stringRedisTemplate.expire(exposedKey, RedisConstants.RECOMMEND_EXPOSED_TTL_HOURS, TimeUnit.HOURS);
        // 2. 容量限制：每个用户最多存 1000 条曝光记录，超出后随机删除旧数据
        Long exposedCount = stringRedisTemplate.opsForSet().size(exposedKey);
        if (exposedCount != null && exposedCount > 1000) {
            // 随机弹出 100 条旧曝光记录（FIFO 近似）
            stringRedisTemplate.opsForSet().pop(exposedKey, 100);
        }
    }

    /**
     * 根据内容类型解析画像流召回的 latest 池 key（对齐 ContentServiceImpl 既有选池规则）。
     */
    private String resolveRecommendKey(Integer contentType) {
        if (contentType == null) {
            return RedisConstants.RECOMMEND_ALL_KEY;
        }
        if (contentType == 1) {
            return RedisConstants.RECOMMEND_LIFE_KEY;
        }
        if (contentType == 2) {
            return RedisConstants.RECOMMEND_PROFESSIONAL_KEY;
        }
        throw new ContentFailedException("内容类型必须为 1 或 2");
    }

    /**
     * 根据内容类型解析画像流召回的 hot 池 key（对齐 ContentServiceImpl 既有选池规则）。
     */
    private String resolveRecommendHotKey(Integer contentType) {
        if (contentType == null) {
            return RedisConstants.RECOMMEND_HOT_ALL_KEY;
        }
        if (contentType == 1) {
            return RedisConstants.RECOMMEND_HOT_LIFE_KEY;
        }
        if (contentType == 2) {
            return RedisConstants.RECOMMEND_HOT_PROFESSIONAL_KEY;
        }
        throw new ContentFailedException("内容类型必须为 1 或 2");
    }
}
