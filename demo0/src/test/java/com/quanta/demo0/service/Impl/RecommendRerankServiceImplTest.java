package com.quanta.demo0.service.Impl;

import com.quanta.demo0.constant.RedisConstants;
import com.quanta.demo0.content.entity.Content;
import com.quanta.demo0.mapper.ContentMapper;
import com.quanta.demo0.feed.properties.RecommendProperties;
import com.quanta.demo0.service.RecommendRerankService;
import com.quanta.demo0.service.UserProfileService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * RecommendRerankServiceImpl 画像流重排核心测试（推荐流个性化 03 Task 3.2，D6/D7）。
 * 断言：双池召回按 contentId 去重合并且不读 ZSET score；曝光过滤剔除已曝光；
 * MySQL 可见性兜底过滤；候选集内 min-max 热度归一化（匿名=纯热度序）；
 * 匹配分=交集标签权重/画像标签总权重（不含 __total）；α 过渡新用户纯热度、饱和用户画像主导；
 * finalScore 降序 + 同分 contentId 降序；截断与 hasMore 边界；匿名不读画像不读写曝光；
 * 登录用户回写曝光（key/TTL 与既有 hot 流曝光语义一致）。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RecommendRerankServiceImplTest {

    private static final Long USER_ID = 3L;
    private static final String HOT_ALL_KEY = RedisConstants.RECOMMEND_HOT_ALL_KEY;
    private static final String LATEST_ALL_KEY = RedisConstants.RECOMMEND_ALL_KEY;
    private static final String HOT_LIFE_KEY = RedisConstants.RECOMMEND_HOT_LIFE_KEY;
    private static final String LIFE_KEY = RedisConstants.RECOMMEND_LIFE_KEY;
    private static final String EXPOSED_KEY = RedisConstants.RECOMMEND_EXPOSED_KEY_PREFIX + USER_ID;

    @Mock
    private StringRedisTemplate stringRedisTemplate;
    @Mock
    private ZSetOperations<String, String> zSetOperations;
    @Mock
    private SetOperations<String, String> setOperations;
    @Mock
    private ContentMapper contentMapper;
    @Mock
    private UserProfileService userProfileService;

    private final RecommendProperties recommendProperties = new RecommendProperties();

    private RecommendRerankServiceImpl service;

    @BeforeEach
    void setUp() {
        when(stringRedisTemplate.opsForZSet()).thenReturn(zSetOperations);
        when(stringRedisTemplate.opsForSet()).thenReturn(setOperations);
        service = new RecommendRerankServiceImpl(
                stringRedisTemplate, contentMapper, userProfileService, recommendProperties);
    }

    // ------------------------- 测试数据构造 -------------------------

    /** 审核通过、未删除的帖子（createTime 统一 1 小时前，热度差异只来自互动计数） */
    private Content content(Long contentId, Integer contentType, int liked, int commentCount, int collectCount) {
        return Content.builder()
                .contentId(contentId)
                .contentType(contentType)
                .liked(liked)
                .commentCount(commentCount)
                .collectCount(collectCount)
                .auditStatus(1)
                .isDeleted(0)
                .createTime(LocalDateTime.now().minusHours(1))
                .build();
    }

    /** 互动全零的帖子（保底分，热度最低档） */
    private Content zeroContent(Long contentId, Integer contentType) {
        return content(contentId, contentType, 0, 0, 0);
    }

    private void stubRecall(Set<String> hotIds, Set<String> latestIds) {
        when(zSetOperations.reverseRange(HOT_ALL_KEY, 0, 149)).thenReturn(hotIds);
        when(zSetOperations.reverseRange(LATEST_ALL_KEY, 0, 149)).thenReturn(latestIds);
    }

    private void stubEmptyExposure() {
        when(setOperations.members(EXPOSED_KEY)).thenReturn(new LinkedHashSet<>());
        when(setOperations.size(EXPOSED_KEY)).thenReturn(0L);
    }

    /** mock 画像服务的标签解析：contentType 1→life / 2→professional（对齐 D5 真实语义） */
    private void stubTagResolution() {
        when(userProfileService.resolveContentTags(any(Content.class))).thenAnswer(invocation -> {
            Content content = invocation.getArgument(0);
            if (content.getContentType() == null) {
                return List.of();
            }
            if (content.getContentType() == 1) {
                return List.of("life");
            }
            if (content.getContentType() == 2) {
                return List.of("professional");
            }
            return List.of();
        });
    }

    private List<Long> resultIds(RecommendRerankService.RerankResult result) {
        return result.contents().stream().map(Content::getContentId).collect(Collectors.toList());
    }

    // ------------------------- 召回与过滤 -------------------------

    @Test
    void 双池召回_同帖去重只留一份() {
        // hot 池 [1,2,3]、latest 池 [3,4,5]：contentId=3 两池重复，合并后 5 个候选
        stubRecall(new LinkedHashSet<>(List.of("1", "2", "3")), new LinkedHashSet<>(List.of("3", "4", "5")));
        when(contentMapper.selectBatchIds(anyList())).thenReturn(List.of(
                content(1L, 1, 10, 0, 0),
                content(2L, 2, 20, 0, 0),
                content(3L, 1, 30, 0, 0),
                content(4L, 2, 40, 0, 0),
                content(5L, 1, 50, 0, 0)
        ));

        RecommendRerankService.RerankResult result = service.rerank(null, null, 10);

        // 去重后候选 5 个（不含重复的 3），匿名纯热度序 [5,4,3,2,1]
        assertEquals(List.of(5L, 4L, 3L, 2L, 1L), resultIds(result));
        assertFalse(result.hasMore());
        // 召回条数来自配置：默认 recallHotSize/recallLatestSize=150 → reverseRange(key, 0, 149)
        verify(zSetOperations).reverseRange(HOT_ALL_KEY, 0, 149);
        verify(zSetOperations).reverseRange(LATEST_ALL_KEY, 0, 149);
    }

    @Test
    void contentType分类池_画像流路由到life池() {
        when(zSetOperations.reverseRange(HOT_LIFE_KEY, 0, 149))
                .thenReturn(new LinkedHashSet<>(List.of("1")));
        when(zSetOperations.reverseRange(LIFE_KEY, 0, 149))
                .thenReturn(new LinkedHashSet<>(List.of("2")));
        when(contentMapper.selectBatchIds(anyList())).thenReturn(List.of(
                content(1L, 1, 10, 0, 0),
                content(2L, 1, 20, 0, 0)
        ));

        RecommendRerankService.RerankResult result = service.rerank(null, 1, 5);

        // 分类 tab 透传：只从 life 池召回，全量池不被触碰
        verify(zSetOperations, never()).reverseRange(eq(HOT_ALL_KEY), anyLong(), anyLong());
        verify(zSetOperations, never()).reverseRange(eq(LATEST_ALL_KEY), anyLong(), anyLong());
        assertEquals(List.of(2L, 1L), resultIds(result));
    }

    @Test
    void 曝光过滤_已曝光帖子被剔除() {
        stubRecall(new LinkedHashSet<>(List.of("1", "2", "3")), new LinkedHashSet<>());
        // 用户已曝光 contentId=2（隐式游标）
        when(setOperations.members(EXPOSED_KEY)).thenReturn(new LinkedHashSet<>(List.of("2")));
        when(setOperations.size(EXPOSED_KEY)).thenReturn(1L);
        when(contentMapper.selectBatchIds(anyList())).thenReturn(List.of(
                content(1L, 1, 10, 0, 0),
                content(3L, 1, 30, 0, 0)
        ));
        when(userProfileService.getProfile(USER_ID)).thenReturn(Map.of());

        RecommendRerankService.RerankResult result = service.rerank(USER_ID, null, 5);

        assertEquals(List.of(3L, 1L), resultIds(result));
    }

    @Test
    void MySQL可见性兜底_驳回与已删帖被过滤() {
        stubRecall(new LinkedHashSet<>(List.of("1", "2", "3")), new LinkedHashSet<>());
        Content rejected = content(2L, 1, 999, 0, 0);
        rejected.setAuditStatus(2); // 驳回
        Content deleted = content(3L, 1, 999, 0, 0);
        deleted.setIsDeleted(1); // 已删
        when(contentMapper.selectBatchIds(anyList())).thenReturn(List.of(
                content(1L, 1, 10, 0, 0), rejected, deleted));

        RecommendRerankService.RerankResult result = service.rerank(null, null, 5);

        // ZSET 时差由 MySQL 兜底：驳回/已删帖不进入画像流
        assertEquals(List.of(1L), resultIds(result));
    }

    // ------------------------- 算分与排序 -------------------------

    @Test
    void 热度归一化_候选集内最热优先最低殿后_单候选正常返回() {
        // 3 候选不同计数：id2=50 最热(normHot=1.0)、id1=30 居中、id3=20 最低(normHot=0)
        stubRecall(new LinkedHashSet<>(List.of("1", "2", "3")), new LinkedHashSet<>());
        when(contentMapper.selectBatchIds(anyList())).thenReturn(List.of(
                content(1L, 1, 10, 0, 0),      // baseScore=30
                content(2L, 1, 0, 0, 10),      // baseScore=50
                content(3L, 1, 0, 10, 0)       // baseScore=20
        ));

        RecommendRerankService.RerankResult result = service.rerank(null, null, 5);

        // 匿名 α=1：finalScore=normHot，排序即 [最热, 居中, 最低]
        assertEquals(List.of(2L, 1L, 3L), resultIds(result));

        // 单候选=1.0：只剩一个候选时仍正常返回（归一化分母为 0 的边界）
        stubRecall(new LinkedHashSet<>(List.of("9")), new LinkedHashSet<>());
        when(contentMapper.selectBatchIds(anyList())).thenReturn(List.of(zeroContent(9L, 1)));
        RecommendRerankService.RerankResult single = service.rerank(null, null, 5);
        assertEquals(List.of(9L), resultIds(single));
        assertFalse(single.hasMore());
    }

    @Test
    void 匹配分_画像life权重占八成_life中热帖压过professional最热帖() {
        // 画像 {life:8, professional:2, __total:100}：__total≥阈值 100 → S=1 → α=0.4
        // life 帖 matchScore=8/10=0.8；professional 帖 matchScore=2/10=0.2
        when(userProfileService.getProfile(USER_ID)).thenReturn(Map.of(
                "life", 8.0, "professional", 2.0, "__total", 100.0));
        stubTagResolution();
        stubRecall(new LinkedHashSet<>(List.of("1", "2", "3")), new LinkedHashSet<>());
        when(contentMapper.selectBatchIds(anyList())).thenReturn(List.of(
                content(1L, 1, 10, 0, 0),        // life 中热帖：hotScore=30/3^1.5≈5.77，normHot=(5.77-3.85)/(9.62-3.85)≈0.33
                content(2L, 2, 0, 0, 10),        // professional 最热帖：hotScore=50/3^1.5≈9.62 → normHot=1.0
                zeroContent(3L, 1)               // life 零互动帖：保底分 20/3^1.5≈3.85（候选集最低）→ normHot=0
        ));
        stubEmptyExposure();

        RecommendRerankService.RerankResult result = service.rerank(USER_ID, null, 5);

        // 零互动帖保底分≈3.85 是归一化下限，参与 min-max：normHot(life中热)=(30-20)/(50-20)=1/3
        // finalScore(life中热)=0.4×(1/3)+0.6×0.8≈0.61；finalScore(prof最热)=0.4×1+0.6×0.2=0.52；
        // finalScore(life零热)=0.4×0+0.6×0.8=0.48 → 匹配分 0.8 把 life 中热帖顶到 professional 最热帖前
        assertEquals(List.of(1L, 2L, 3L), resultIds(result));
    }

    @Test
    void 画像饱和_alpha下限生效_life帖排到professional高热帖前() {
        // 画像只剩 life（professional 兴趣已衰减删除）：life 帖 matchScore=10/10=1.0，professional 帖 0
        when(userProfileService.getProfile(USER_ID)).thenReturn(Map.of(
                "life", 10.0, "__total", 100.0));
        stubTagResolution();
        stubRecall(new LinkedHashSet<>(List.of("1", "2")), new LinkedHashSet<>());
        when(contentMapper.selectBatchIds(anyList())).thenReturn(List.of(
                zeroContent(1L, 1),                // life 帖：normHot=0
                content(2L, 2, 0, 0, 10)           // professional 帖：baseScore=50 → normHot=1.0
        ));
        stubEmptyExposure();

        RecommendRerankService.RerankResult result = service.rerank(USER_ID, null, 5);

        // S=1 → α=alphaMin=0.4：life 帖 0.4×0+0.6×1=0.6 > professional 帖 0.4×1+0.6×0=0.4 → 画像主导
        assertEquals(List.of(1L, 2L), resultIds(result));
    }

    @Test
    void 新用户α接近1_顺序等于纯热度序() {
        // 画像 {life:5, __total:5}：S=5/100=0.05 → α=1-0.05×0.6=0.97，热度占绝对主导
        when(userProfileService.getProfile(USER_ID)).thenReturn(Map.of(
                "life", 5.0, "__total", 5.0));
        stubTagResolution();
        stubRecall(new LinkedHashSet<>(List.of("1", "2")), new LinkedHashSet<>());
        when(contentMapper.selectBatchIds(anyList())).thenReturn(List.of(
                zeroContent(1L, 1),                // life 帖：normHot=0
                content(2L, 2, 0, 0, 10)           // professional 帖：normHot=1.0
        ));
        stubEmptyExposure();

        RecommendRerankService.RerankResult result = service.rerank(USER_ID, null, 5);

        // professional 帖：0.97×1+0.03×0=0.97 > life 帖：0.97×0+0.03×1=0.03 → 与纯热度序一致
        assertEquals(List.of(2L, 1L), resultIds(result));
    }

    @Test
    void 同分候选_contentId降序稳定排序() {
        // 两帖计数完全相同（finalScore 相同）→ contentId 降序，召回顺序不影响结果
        stubRecall(new LinkedHashSet<>(List.of("7", "9")), new LinkedHashSet<>());
        when(contentMapper.selectBatchIds(anyList())).thenReturn(List.of(
                content(7L, 1, 10, 0, 0),
                content(9L, 1, 10, 0, 0)
        ));

        RecommendRerankService.RerankResult result = service.rerank(null, null, 5);

        assertEquals(List.of(9L, 7L), resultIds(result));
    }

    // ------------------------- 截断、hasMore 与曝光回写 -------------------------

    @Test
    void 候选超页大小_截断到pageSize且hasMore为true() {
        stubRecall(new LinkedHashSet<>(List.of("1", "2", "3", "4", "5", "6")), new LinkedHashSet<>());
        when(contentMapper.selectBatchIds(anyList())).thenReturn(List.of(
                content(1L, 1, 10, 0, 0),
                content(2L, 1, 20, 0, 0),
                content(3L, 1, 30, 0, 0),
                content(4L, 1, 40, 0, 0),
                content(5L, 1, 50, 0, 0),
                content(6L, 1, 60, 0, 0)
        ));

        RecommendRerankService.RerankResult result = service.rerank(null, null, 5);

        // 6 候选截 5：只返回热度前 5，hasMore=true
        assertEquals(List.of(6L, 5L, 4L, 3L, 2L), resultIds(result));
        assertTrue(result.hasMore());
    }

    @Test
    void 候选等于页大小_hasMore为false() {
        stubRecall(new LinkedHashSet<>(List.of("1", "2", "3", "4", "5")), new LinkedHashSet<>());
        when(contentMapper.selectBatchIds(anyList())).thenReturn(List.of(
                content(1L, 1, 10, 0, 0),
                content(2L, 1, 20, 0, 0),
                content(3L, 1, 30, 0, 0),
                content(4L, 1, 40, 0, 0),
                content(5L, 1, 50, 0, 0)
        ));

        RecommendRerankService.RerankResult result = service.rerank(null, null, 5);

        // 候选=pageSize=5：全量返回，池子已耗尽
        assertEquals(5, result.contents().size());
        assertFalse(result.hasMore());
    }

    @Test
    void 登录用户_本页内容回写曝光set含TTL() {
        stubRecall(new LinkedHashSet<>(List.of("1", "2", "3")), new LinkedHashSet<>());
        when(contentMapper.selectBatchIds(anyList())).thenReturn(List.of(
                content(1L, 1, 10, 0, 0),
                content(2L, 1, 20, 0, 0),
                content(3L, 1, 30, 0, 0)
        ));
        when(userProfileService.getProfile(USER_ID)).thenReturn(Map.of());
        stubEmptyExposure();

        RecommendRerankService.RerankResult result = service.rerank(USER_ID, null, 2);

        // 本页 = 热度前 2（id3、id2）被记入曝光 set（隐式游标），TTL 24 小时
        verify(setOperations).add(eq(EXPOSED_KEY),
                org.mockito.AdditionalMatchers.aryEq(new String[]{"3", "2"}));
        verify(stringRedisTemplate).expire(eq(EXPOSED_KEY), eq(24L), eq(TimeUnit.HOURS));
        assertTrue(result.hasMore());
    }

    @Test
    void 匿名用户_不读画像不读写曝光_纯热度序() {
        stubRecall(new LinkedHashSet<>(List.of("1", "2")), new LinkedHashSet<>());
        when(contentMapper.selectBatchIds(anyList())).thenReturn(List.of(
                content(1L, 1, 10, 0, 0),
                content(2L, 1, 20, 0, 0)
        ));

        RecommendRerankService.RerankResult result = service.rerank(null, null, 5);

        // 匿名：α=1 纯热度，不触碰画像与曝光
        assertEquals(List.of(2L, 1L), resultIds(result));
        verify(userProfileService, never()).getProfile(any());
        verifyNoInteractions(setOperations);
        verify(stringRedisTemplate, never()).opsForSet();
    }

    @Test
    void 空召回_返回空列表hasMore为false_不查库不读画像() {
        stubRecall(new LinkedHashSet<>(), new LinkedHashSet<>());

        RecommendRerankService.RerankResult result = service.rerank(USER_ID, null, 5);

        // 池子耗尽：零召回短路返回，不触发 DB/画像/曝光
        assertEquals(new ArrayList<>(), result.contents());
        assertFalse(result.hasMore());
        verify(contentMapper, never()).selectBatchIds(anyList());
        verify(userProfileService, never()).getProfile(any());
        verifyNoInteractions(setOperations);
    }

    @Test
    void 曝光全部命中_候选耗尽返回空页() {
        // 3 候选全部已曝光（翻页到底）：过滤后零候选 → 空页 hasMore=false
        stubRecall(new LinkedHashSet<>(List.of("1", "2", "3")), new LinkedHashSet<>());
        when(setOperations.members(EXPOSED_KEY))
                .thenReturn(new LinkedHashSet<>(List.of("1", "2", "3")));
        when(setOperations.size(EXPOSED_KEY)).thenReturn(3L);
        when(userProfileService.getProfile(USER_ID)).thenReturn(Map.of());

        RecommendRerankService.RerankResult result = service.rerank(USER_ID, null, 5);

        assertEquals(new ArrayList<>(), result.contents());
        assertFalse(result.hasMore());
        // 空页不回写曝光
        verify(setOperations, never()).add(anyString(), any(String[].class));
    }

    @Test
    void 自定义召回条数_配置注入生效() {
        // 收口验证：召回条数从配置读取（非硬编码 150）
        recommendProperties.getProfile().setRecallHotSize(30);
        recommendProperties.getProfile().setRecallLatestSize(20);
        when(zSetOperations.reverseRange(HOT_ALL_KEY, 0, 29))
                .thenReturn(new LinkedHashSet<>(List.of("1")));
        when(zSetOperations.reverseRange(LATEST_ALL_KEY, 0, 19))
                .thenReturn(new LinkedHashSet<>(List.of("2")));
        when(contentMapper.selectBatchIds(anyList())).thenReturn(List.of(
                content(1L, 1, 10, 0, 0),
                content(2L, 1, 20, 0, 0)
        ));

        RecommendRerankService.RerankResult result = service.rerank(null, null, 5);

        verify(zSetOperations, atLeastOnce()).reverseRange(HOT_ALL_KEY, 0, 29);
        verify(zSetOperations, atLeastOnce()).reverseRange(LATEST_ALL_KEY, 0, 19);
        assertEquals(List.of(2L, 1L), resultIds(result));
    }
}
