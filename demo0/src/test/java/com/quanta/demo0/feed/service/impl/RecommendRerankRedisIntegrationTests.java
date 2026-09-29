package com.quanta.demo0.feed.service.impl;

import com.quanta.demo0.feed.service.impl.UserInterestProfileServiceImpl;


import com.quanta.demo0.platform.redis.utils.RedisTaskLockAdapter;
import com.quanta.demo0.platform.redis.constant.RedisConstants;
import com.quanta.demo0.content.service.ContentQueryService;
import com.quanta.demo0.content.vo.ContentSnapshotVO;
import com.quanta.demo0.feed.properties.RecommendProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 画像流重排真实 Redis 集成测试（推荐流个性化 03 Task 3.4，D6/D7/D10/D12）。
 * 模块：推荐流个性化 —— Testcontainers redis:7.2-alpine 动态端口验证真实 Redis 行为。
 * 职责：①画像累加端到端（applyBehavior → 画像 Hash field 与 __total，D10 无 TTL）；
 * ②曝光 set 隐式游标（同用户连续拉取不重复、池子耗尽 hasMore=false、TTL 24h）；
 * ③双用户画像差异 → 同数据集返回顺序不同（α 过渡真实生效）；
 * ④匿名不写曝光 set；⑤衰减任务对真实 Hash 的衰减/删除与 D12 辅助 key 隔离。
 * 边界：内容查询端口用 Mockito mock，仅 Redis 用真实容器；
 * 不起 Spring 上下文，服务实例手动组装（对齐 TrendingCacheRedisIntegrationTests 模式）；
 * 算分公式的数值细节由 RecommendRerankServiceImplTest 单测承担，本类验证服务间真实协作。
 */
@Testcontainers
class RecommendRerankRedisIntegrationTests {

    private static final String HOT_ALL_KEY = RedisConstants.RECOMMEND_HOT_ALL_KEY;
    private static final String LATEST_ALL_KEY = RedisConstants.RECOMMEND_ALL_KEY;
    private static final String TOTAL_FIELD = RedisConstants.USER_PROFILE_TOTAL_FIELD;

    /** 各场景独立用户段，避免共享容器内测试数据互扰 */
    private static final Long USER_ACCUMULATE = 1L;
    private static final Long USER_CURSOR = 2L;
    private static final Long USER_PROFILE_HEAVY = 11L;
    private static final Long USER_COLD = 12L;
    private static final Long USER_DECAY = 601L;
    private static final Long USER_DECAY_EMPTY = 602L;

    @Container
    private static final GenericContainer<?> REDIS = new GenericContainer<>(
            DockerImageName.parse("redis:7.2-alpine")
    ).withExposedPorts(6379);

    private LettuceConnectionFactory connectionFactory;
    private StringRedisTemplate redisTemplate;

    /** 内容事实查询端口 mock：按 contentById 应答，未预置 id 视为不存在 */
    private ContentQueryService contentQueryService;
    private UserInterestProfileServiceImpl userProfileService;
    private RecommendRerankServiceImpl rerankService;

    /** 按帖 id 索引的测试数据集：mock 内容快照查询端口的应答源 */
    private final Map<Long, ContentSnapshotVO> contentById = new HashMap<>();

    @BeforeEach
    void setUp() {
        connectionFactory = new LettuceConnectionFactory(
                REDIS.getHost(),
                REDIS.getMappedPort(6379)
        );
        connectionFactory.afterPropertiesSet();

        redisTemplate = new StringRedisTemplate(connectionFactory);
        redisTemplate.afterPropertiesSet();
        cleanupRecommendNamespaces();

        contentById.clear();
        contentQueryService = mock(ContentQueryService.class);
        userProfileService = new UserInterestProfileServiceImpl(contentQueryService, redisTemplate);
        rerankService = new RecommendRerankServiceImpl(
                redisTemplate, contentQueryService, userProfileService, new RecommendProperties());
    }

    @AfterEach
    void tearDown() {
        if (connectionFactory != null) {
            connectionFactory.destroy();
        }
    }

    // ------------------------- 场景 1：画像累加端到端 -------------------------

    @Test
    void applyBehaviorAccumulatesRealProfileHashFieldsAndTotal() {
        stubContent(content(101L, 1, 0, 0, 0));
        stubContent(content(102L, 2, 0, 0, 0));
        stubContent(deletedContent(103L, 1));

        // 同一 life 帖两次点赞权重（2.0×2）+ 一次 professional 收藏权重（3.0）
        userProfileService.applyBehavior(USER_ACCUMULATE, 101L, 2.0);
        userProfileService.applyBehavior(USER_ACCUMULATE, 101L, 2.0);
        userProfileService.applyBehavior(USER_ACCUMULATE, 102L, 3.0);
        // 已删除帖：事实源校验生效，不累加（防脏画像端到端证据）
        userProfileService.applyBehavior(USER_ACCUMULATE, 103L, 5.0);

        Map<String, Double> profile = userProfileService.getProfile(USER_ACCUMULATE);
        assertThat(profile.get("life")).isCloseTo(4.0, within(1e-9));
        assertThat(profile.get("professional")).isCloseTo(3.0, within(1e-9));
        assertThat(profile.get(TOTAL_FIELD)).isCloseTo(7.0, within(1e-9));

        // 真实 Hash 落在 user:profile:{userId} 命名空间，且不设 TTL（D10：丢失后重新积累）
        String profileKey = RedisConstants.USER_PROFILE_KEY + USER_ACCUMULATE;
        assertThat(redisTemplate.hasKey(profileKey)).isTrue();
        assertThat(redisTemplate.getExpire(profileKey)).isEqualTo(-1L);
    }

    // ------------------------- 场景 2：曝光 set 隐式游标 -------------------------

    @Test
    void repeatedRerankUsesExposureSetAsImplicitCursorUntilPoolExhausted() {
        // 预置 5 帖（liked 递减 = 热度降序），latest 池放 2 个重复 member 验证跨池召回去重
        stubContent(content(110L, 1, 100, 0, 0));
        stubContent(content(111L, 2, 80, 0, 0));
        stubContent(content(112L, 1, 60, 0, 0));
        stubContent(content(113L, 2, 40, 0, 0));
        stubContent(content(114L, 1, 20, 0, 0));
        seedRecallPool(HOT_ALL_KEY, seedScores(110L, 100.0, 111L, 80.0, 112L, 60.0, 113L, 40.0, 114L, 20.0));
        seedRecallPool(LATEST_ALL_KEY, seedScores(110L, 900.0, 111L, 800.0));

        String exposedKey = RedisConstants.RECOMMEND_EXPOSED_KEY_PREFIX + USER_CURSOR;

        // 第一次：热度 top2，回写曝光
        var first = rerankService.rerank(USER_CURSOR, null, 2);
        assertThat(contentIds(first.contents())).containsExactly(110L, 111L);
        assertThat(first.hasMore()).isTrue();

        // 曝光 set 真实写入：本页内容 + TTL 24h
        assertThat(redisTemplate.opsForSet().members(exposedKey))
                .containsExactlyInAnyOrder("110", "111");
        assertThat(redisTemplate.getExpire(exposedKey, TimeUnit.SECONDS))
                .as("exposure set TTL must be 24 hours")
                .isBetween(86_000L, 86_400L);

        // 第二次：隐式游标生效，不含第一次返回的任何 contentId
        var second = rerankService.rerank(USER_CURSOR, null, 2);
        assertThat(contentIds(second.contents())).containsExactly(112L, 113L);
        assertThat(second.hasMore()).isTrue();
        assertThat(contentIds(second.contents())).doesNotContainAnyElementsOf(contentIds(first.contents()));

        // 第三次：池子只剩 1 帖，返回它但 hasMore=false
        var third = rerankService.rerank(USER_CURSOR, null, 2);
        assertThat(contentIds(third.contents())).containsExactly(114L);
        assertThat(third.hasMore()).isFalse();

        // 第四次：候选耗尽，空页 hasMore=false（稳定可重复）
        var fourth = rerankService.rerank(USER_CURSOR, null, 2);
        assertThat(fourth.contents()).isEmpty();
        assertThat(fourth.hasMore()).isFalse();
    }

    // ------------------------- 场景 3：双用户画像差异 -------------------------

    @Test
    void usersWithDifferentProfilesGetDifferentOrderingOnSameDataset() {
        // 同一数据集：life 帖低热（liked=10）、professional 帖高热（liked=1000）
        stubContent(content(201L, 1, 10, 0, 0));
        stubContent(content(202L, 2, 1000, 0, 0));
        seedRecallPool(HOT_ALL_KEY, seedScores(201L, 1.0, 202L, 2.0));
        seedRecallPool(LATEST_ALL_KEY, seedScores(201L, 900.0));

        // 用户 11：life 画像饱和（34×3=102 ≥ 阈值 100 → S=1 → α=alphaMin=0.4，画像主导）
        when(contentQueryService.getContentSnapshot(201L)).thenReturn(content(201L, 1, 0, 0, 0));
        userProfileService.applyBehavior(USER_PROFILE_HEAVY, 201L, 34.0);
        userProfileService.applyBehavior(USER_PROFILE_HEAVY, 201L, 34.0);
        userProfileService.applyBehavior(USER_PROFILE_HEAVY, 201L, 34.0);

        Map<String, Double> heavyProfile = userProfileService.getProfile(USER_PROFILE_HEAVY);
        assertThat(heavyProfile.get("life")).isCloseTo(102.0, within(1e-9));
        assertThat(heavyProfile.get(TOTAL_FIELD)).isCloseTo(102.0, within(1e-9));

        // 饱和画像用户：life 帖 matchScore=1.0 主导排序，压过高热 professional 帖
        var heavy = rerankService.rerank(USER_PROFILE_HEAVY, null, 2);
        assertThat(contentIds(heavy.contents())).containsExactly(201L, 202L);

        // 冷启动用户：无画像 → S=0 → α=1 纯热度，高热 professional 帖在前
        var cold = rerankService.rerank(USER_COLD, null, 2);
        assertThat(contentIds(cold.contents())).containsExactly(202L, 201L);

        // 同一数据集、同一时刻：两用户顺序恰好相反（个性化生效的端到端证据）
        assertThat(contentIds(heavy.contents())).isNotEqualTo(contentIds(cold.contents()));
    }

    // ------------------------- 场景 4：匿名不写曝光 set -------------------------

    @Test
    void anonymousRerankNeverWritesExposureSet() {
        stubContent(content(210L, 1, 100, 0, 0));
        stubContent(content(211L, 2, 80, 0, 0));
        stubContent(content(212L, 1, 60, 0, 0));
        seedRecallPool(HOT_ALL_KEY, seedScores(210L, 100.0, 211L, 80.0, 212L, 60.0));
        seedRecallPool(LATEST_ALL_KEY, seedScores(212L, 700.0));

        var first = rerankService.rerank(null, null, 2);
        assertThat(contentIds(first.contents())).containsExactly(210L, 211L);
        assertThat(first.hasMore()).isTrue();

        // 全库无任何曝光 key（匿名不写曝光）
        assertThat(redisTemplate.keys(RedisConstants.RECOMMEND_EXPOSED_KEY_PREFIX + "*")).isEmpty();

        // 匿名连续拉取结果不变（不读曝光 → 无游标状态的端到端证据）
        var second = rerankService.rerank(null, null, 2);
        assertThat(contentIds(second.contents())).containsExactlyElementsOf(contentIds(first.contents()));
        assertThat(redisTemplate.keys(RedisConstants.RECOMMEND_EXPOSED_KEY_PREFIX + "*")).isEmpty();
    }

    // ------------------------- 场景 5：衰减任务对真实 Hash 的效果 -------------------------

    @Test
    void decayTaskDecaysRealHashAndKeepsAuxiliaryKeysIsolated() {
        // D12 隔离验证对象：画像域辅助 String key（连字符前缀）
        String watermarkKey = RedisConstants.USER_PROFILE_SYNC_WATERMARK_KEY;
        redisTemplate.opsForValue().set(watermarkKey, "2026-09-24T00:00:00");

        // 真实 applyBehavior 构造画像：601 → life=2.0、professional=0.4、__total=2.4
        when(contentQueryService.getContentSnapshot(301L)).thenReturn(content(301L, 1, 0, 0, 0));
        when(contentQueryService.getContentSnapshot(302L)).thenReturn(content(302L, 2, 0, 0, 0));
        userProfileService.applyBehavior(USER_DECAY, 301L, 2.0);
        userProfileService.applyBehavior(USER_DECAY, 302L, 0.2);
        userProfileService.applyBehavior(USER_DECAY, 302L, 0.2);
        // 602 → life=0.1、__total=0.1（衰减后全 field 低于 0.5 → Hash 删空）
        userProfileService.applyBehavior(USER_DECAY_EMPTY, 301L, 0.1);

        // 默认衰减参数：factor=0.95、minScore=0.5（唯一真源 RecommendProperties.Profile）
        new ProfileDecayTask(new RedisTaskLockAdapter(redisTemplate), redisTemplate, new RecommendProperties())
                .decayAllProfiles();

        // 主用户：field 按因子缩放，低于阈值的 professional 被删除，__total 同步衰减
        String profileKey = RedisConstants.USER_PROFILE_KEY + USER_DECAY;
        Map<Object, Object> entries = redisTemplate.opsForHash().entries(profileKey);
        assertThat(String.valueOf(entries.get("life")))
                .as("life 2.0 × 0.95 must survive above min score")
                .isEqualTo(String.valueOf(2.0 * 0.95));
        assertThat(Double.parseDouble(String.valueOf(entries.get(TOTAL_FIELD))))
                .as("__total must decay in step with tag fields")
                .isCloseTo(2.4 * 0.95, within(1e-9));
        assertThat(entries).doesNotContainKey("professional");

        // 低阈值用户：全部 field 低于 0.5，Hash 删空（key 随最后 field 删除而消失）
        assertThat(userProfileService.getProfile(USER_DECAY_EMPTY)).isEmpty();

        // D12：SCAN user:profile:* 只命中画像 Hash，辅助 String key 不混入扫描结果
        assertThat(scanProfileKeys(RedisConstants.USER_PROFILE_KEY + "*"))
                .containsExactly(profileKey);

        // 辅助 key 完好：类型未被破坏、值未被改动、未被误删
        assertThat(redisTemplate.opsForValue().get(watermarkKey)).isEqualTo("2026-09-24T00:00:00");

        // 衰减锁跑完即删（finally unlock 正常路径）
        assertThat(redisTemplate.hasKey(RedisConstants.USER_PROFILE_DECAY_LOCK_KEY)).isFalse();
    }

    // ------------------------- 测试数据构造与辅助 -------------------------

    /** 审核通过、未删除的帖子（createTime 统一 1 小时前，热度差异只来自互动计数） */
    private ContentSnapshotVO content(Long contentId, Integer contentType, int liked, int commentCount, int collectCount) {
        return ContentSnapshotVO.builder()
                .contentId(contentId)
                .contentType(contentType)
                .likedCount(liked)
                .commentCount(commentCount)
                .collectCount(collectCount)
                .auditStatus(1)
                .isDeleted(0)
                .createTime(LocalDateTime.now().minusHours(1))
                .build();
    }

    /** 已删除帖子：验证画像累加的事实源校验（非法帖跳过） */
    private ContentSnapshotVO deletedContent(Long contentId, Integer contentType) {
        ContentSnapshotVO content = content(contentId, contentType, 0, 0, 0);
        content.setIsDeleted(1);
        return content;
    }

    /** 预置测试快照：单条/批量查询按 id 应答（未预置 id 视为不存在） */
    private void stubContent(ContentSnapshotVO content) {
        contentById.put(content.getContentId(), content);
        when(contentQueryService.getContentSnapshot(content.getContentId())).thenReturn(content);
        when(contentQueryService.getContentFactSnapshots(anyList())).thenAnswer(invocation -> {
            List<Long> ids = invocation.getArgument(0);
            return ids.stream()
                    .filter(contentById::containsKey)
                    .map(contentById::get)
                    .collect(Collectors.toList());
        });
    }

    /** 构造 ZSET member→score 映射（调用点保持 key/score 数据成对可读） */
    private Map<String, Double> seedScores(Object... idScorePairs) {
        Map<String, Double> memberScores = new LinkedHashMap<>();
        for (int i = 0; i < idScorePairs.length; i += 2) {
            memberScores.put(String.valueOf(idScorePairs[i]), (Double) idScorePairs[i + 1]);
        }
        return memberScores;
    }

    /** 预置召回池 ZSET（member=contentId，score 语义不影响重排——重排分数一律 Java 现算） */
    private void seedRecallPool(String poolKey, Map<String, Double> memberScores) {
        memberScores.forEach((member, score) -> redisTemplate.opsForZSet().add(poolKey, member, score));
    }

    /** 清理画像/曝光/召回池命名空间，保证共享容器内各测试数据隔离 */
    private void cleanupRecommendNamespaces() {
        Set<String> staleKeys = new HashSet<>();
        for (String pattern : List.of(
                RedisConstants.USER_PROFILE_KEY + "*",
                "user:profile-*",
                RedisConstants.RECOMMEND_EXPOSED_KEY_PREFIX + "*",
                "content:recommend:*")) {
            Set<String> matched = redisTemplate.keys(pattern);
            if (matched != null) {
                staleKeys.addAll(matched);
            }
        }
        if (!staleKeys.isEmpty()) {
            redisTemplate.delete(staleKeys);
        }
    }

    /** SCAN 收集匹配 key（对齐 ProfileDecayTask 的分批迭代方式） */
    private List<String> scanProfileKeys(String pattern) {
        List<String> keys = new ArrayList<>();
        try (Cursor<String> cursor = redisTemplate.scan(
                ScanOptions.scanOptions().match(pattern).count(500L).build())) {
            while (cursor.hasNext()) {
                keys.add(cursor.next());
            }
        }
        return keys;
    }

    /** 提取推荐结果 contentId 序列 */
    private List<Long> contentIds(List<ContentSnapshotVO> contents) {
        return contents.stream().map(ContentSnapshotVO::getContentId).collect(Collectors.toList());
    }
}
