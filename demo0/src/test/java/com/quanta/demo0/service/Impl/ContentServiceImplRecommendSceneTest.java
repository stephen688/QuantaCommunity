package com.quanta.demo0.service.Impl;

import com.quanta.demo0.feed.dto.RecommendQueryDTO;
import com.quanta.demo0.platform.security.context.BaseContext;
import com.quanta.demo0.content.entity.Content;
import com.quanta.demo0.user.vo.UserAuthInfoVO;
import com.quanta.demo0.content.exception.ContentFailedException;
import com.quanta.demo0.mapper.ContentMapper;
import com.quanta.demo0.mapper.UserMapper;
import com.quanta.demo0.platform.common.result.ScrollResult;
import com.quanta.demo0.service.RecommendRerankService;
import com.quanta.demo0.content.vo.ContentVO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.core.DefaultTypedTuple;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * recommend() 的 scene 收敛测试（推荐流个性化 03 Task 3.3，D8）。
 * 断言：latest / recommend / null 三种入参都进 rerank 画像流（游标入参忽略、
 * ScrollResult 固定 minScore=null / offset=0、hasMore 透传、VO 装配完整）；
 * hot 保持 ZSET 热度序且零曝光读写、无"滤光降级重拉"；非法 scene 异常语义不变；
 * contentType 分类池透传给 rerank。
 * 契约变化依据：总览 §4（scene=latest 语义切换 + 画像流游标语义，D8 既定决策）。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ContentServiceImplRecommendSceneTest {

    private static final Long USER_ID = 3L;
    private static final String HOT_ALL_KEY = "content:recommend:hot:all";

    @Mock
    private ContentMapper contentMapper;
    @Mock
    private UserMapper userMapper;
    @Mock
    private StringRedisTemplate stringRedisTemplate;
    @Mock
    private ZSetOperations<String, String> zSetOperations;
    @Mock
    private RecommendRerankService recommendRerankService;

    @InjectMocks
    private ContentServiceImpl service;

    @BeforeEach
    void setUp() {
        when(stringRedisTemplate.opsForZSet()).thenReturn(zSetOperations);
        BaseContext.setCurrentId(USER_ID);
    }

    @AfterEach
    void tearDown() {
        BaseContext.removeCurrentId();
    }

    // ------------------------- 测试数据构造 -------------------------

    private RecommendQueryDTO query(String scene, Integer contentType, Integer pageSize,
                                     Double lastScore, Integer offset) {
        return RecommendQueryDTO.builder()
                .scene(scene)
                .contentType(contentType)
                .pageSize(pageSize)
                .lastScore(lastScore)
                .offset(offset)
                .build();
    }

    private Content content(Long contentId, Long publishUserId) {
        return Content.builder()
                .contentId(contentId)
                .contentType(1)
                .publishUserId(publishUserId)
                .liked(10)
                .commentCount(0)
                .collectCount(0)
                .auditStatus(1)
                .isDeleted(0)
                .title("标题" + contentId)
                .build();
    }

    private UserAuthInfoVO author(Long userId, String nickName) {
        return UserAuthInfoVO.builder()
                .userId(userId)
                .nickName(nickName)
                .avatarUrl("http://avatar/" + userId + ".png")
                .build();
    }

    /** rerank mock 返回保持推荐序的 2 帖（作者分别为 101/102） */
    private void stubRerankTwoContents(boolean hasMore) {
        when(recommendRerankService.rerank(any(), any(), anyInt())).thenReturn(
                new RecommendRerankService.RerankResult(
                        List.of(content(11L, 101L), content(12L, 102L)), hasMore));
        when(userMapper.selectUserAuthInfoByIds(anyList())).thenReturn(List.of(
                author(101L, "作者甲"), author(102L, "作者乙")));
        // 登录用户的点赞/收藏高亮：Redis 无记录、DB 无记录 → 未点赞未收藏
        when(zSetOperations.score(anyString(), anyString())).thenReturn(null);
        when(contentMapper.countContentLiked(anyLong(), anyLong())).thenReturn(0);
        when(contentMapper.countContentCollect(anyLong(), anyLong())).thenReturn(0);
        when(contentMapper.selectImagesByContentIds(anyLong())).thenReturn(new ArrayList<>());
    }

    // ------------------------- scene 路由收敛 -------------------------

    @Test
    void scene为latest_进rerank画像流_游标入参忽略_游标字段固定() {
        stubRerankTwoContents(true);

        // lastScore/offset 是旧时间序游标，画像流忽略（曝光 set 为隐式游标）
        ScrollResult result = service.recommend(query("latest", null, 2, 123.45, 7));

        verify(recommendRerankService).rerank(USER_ID, null, 2);
        // 画像流 ScrollResult 契约：minScore=null、offset=0、hasMore 透传
        assertNull(result.getMinScore());
        assertEquals(0, result.getOffset());
        assertTrue(result.getHasMore());
        assertEquals(2, result.getList().size());
    }

    @Test
    void scene为recommend_与latest同路径进rerank() {
        stubRerankTwoContents(false);

        ScrollResult result = service.recommend(query("recommend", null, 2, null, 0));

        // 新增显式画像流参数：与 latest 完全同路径
        verify(recommendRerankService).rerank(USER_ID, null, 2);
        assertFalse(result.getHasMore());
        assertNull(result.getMinScore());
        assertEquals(0, result.getOffset());
    }

    @Test
    void scene为null_默认同latest进rerank() {
        stubRerankTwoContents(false);

        service.recommend(query(null, null, 2, null, 0));

        // 对齐现状 null→latest 的默认行为：null 也进画像流
        verify(recommendRerankService).rerank(USER_ID, null, 2);
    }

    @Test
    void 匿名latest_rerank收到null用户ID() {
        BaseContext.removeCurrentId();
        stubRerankTwoContents(true);

        service.recommend(query("latest", null, 2, null, 0));

        // 匿名可访问语义不变：userId 透传 null，由 rerank 内部走 α=1 纯热度
        verify(recommendRerankService).rerank(null, null, 2);
    }

    @Test
    void contentType透传rerank_分类池保持可用() {
        stubRerankTwoContents(false);

        service.recommend(query("recommend", 1, 2, null, 0));

        // 小程序分类 tab：contentType 透传给 rerank 选 life/professional 池
        verify(recommendRerankService).rerank(USER_ID, 1, 2);
    }

    @Test
    void 画像流VO装配_作者昵称头像与点赞高亮完整() {
        stubRerankTwoContents(true);

        ScrollResult result = service.recommend(query("latest", null, 2, null, 0));

        // 第 8~11 步装配保持：推荐序、作者信息、点赞/收藏高亮
        List<ContentVO> voList = (List<ContentVO>) (List<?>) result.getList();
        assertEquals(2, voList.size());
        assertEquals(11L, voList.get(0).getContentId());
        assertEquals("作者甲", voList.get(0).getNickName());
        assertEquals("http://avatar/101.png", voList.get(0).getAvatarUrl());
        assertFalse(voList.get(0).getIsLiked());
        assertFalse(voList.get(0).getIsCollected());
        assertEquals(12L, voList.get(1).getContentId());
        assertEquals("作者乙", voList.get(1).getNickName());
    }

    @Test
    void 画像流空结果_返回空列表hasMore为false() {
        when(recommendRerankService.rerank(any(), any(), anyInt()))
                .thenReturn(new RecommendRerankService.RerankResult(new ArrayList<>(), false));

        ScrollResult result = service.recommend(query("latest", null, 2, null, 0));

        // 池子耗尽：空页且无更多
        assertEquals(0, result.getList().size());
        assertFalse(result.getHasMore());
        assertNull(result.getMinScore());
        assertEquals(0, result.getOffset());
    }

    // ------------------------- hot 流保持与曝光删除 -------------------------

    @Test
    void scene为hot_返回ZSET热度序_零曝光读写_无降级重拉() {
        // hot 池一批拉够：id2(score=50) > id1(score=30) > id3(score=20)
        Set<ZSetOperations.TypedTuple<String>> tuples = new LinkedHashSet<>(List.of(
                new DefaultTypedTuple<>("1", 30.0),
                new DefaultTypedTuple<>("2", 50.0),
                new DefaultTypedTuple<>("3", 20.0)
        ));
        when(zSetOperations.reverseRangeByScoreWithScores(
                eq(HOT_ALL_KEY), anyDouble(), anyDouble(), anyLong(), anyLong()))
                .thenReturn(tuples);
        // 真实 MyBatis selectBatchIds 只会查传入的 ids（hot 流按 pageSize 截断为 [2,1]），
        // mock 按入参过滤贴合真实行为
        when(contentMapper.selectBatchIds(anyList())).thenAnswer(inv -> {
            List<Long> requested = inv.getArgument(0);
            List<Content> all = List.of(
                    content(2L, 101L), content(1L, 102L), content(3L, 103L));
            return new ArrayList<>(all.stream()
                    .filter(c -> requested.contains(c.getContentId()))
                    .collect(java.util.stream.Collectors.toList()));
        });
        when(userMapper.selectUserAuthInfoByIds(anyList())).thenReturn(new ArrayList<>());
        when(zSetOperations.score(anyString(), anyString())).thenReturn(null);
        when(contentMapper.countContentLiked(anyLong(), anyLong())).thenReturn(0);
        when(contentMapper.countContentCollect(anyLong(), anyLong())).thenReturn(0);
        when(contentMapper.selectImagesByContentIds(anyLong())).thenReturn(new ArrayList<>());

        ScrollResult result = service.recommend(query("hot", null, 2, null, 0));

        // ZSET 热度序保持：[2, 1]（score 50、30）
        List<ContentVO> voList = (List<ContentVO>) (List<?>) result.getList();
        assertEquals(List.of(2L, 1L),
                voList.stream().map(ContentVO::getContentId).collect(java.util.stream.Collectors.toList()));
        assertTrue(result.getHasMore());
        // 零曝光读写：hot 流不再触碰曝光 set
        verify(stringRedisTemplate, never()).opsForSet();
        // 无"滤光降级重拉"：一批拉够后不再发起第二次 ZSET 拉取
        verify(zSetOperations, times(1)).reverseRangeByScoreWithScores(
                eq(HOT_ALL_KEY), anyDouble(), anyDouble(), anyLong(), anyLong());
    }

    @Test
    void scene为hot_不进rerank路径() {
        Set<ZSetOperations.TypedTuple<String>> tuples = new LinkedHashSet<>(List.of(
                new DefaultTypedTuple<>("1", 30.0)
        ));
        when(zSetOperations.reverseRangeByScoreWithScores(
                eq(HOT_ALL_KEY), anyDouble(), anyDouble(), anyLong(), anyLong()))
                .thenReturn(tuples);
        when(contentMapper.selectBatchIds(anyList())).thenReturn(List.of(content(1L, 101L)));
        when(userMapper.selectUserAuthInfoByIds(anyList())).thenReturn(new ArrayList<>());
        when(zSetOperations.score(anyString(), anyString())).thenReturn(null);
        when(contentMapper.countContentLiked(anyLong(), anyLong())).thenReturn(0);
        when(contentMapper.countContentCollect(anyLong(), anyLong())).thenReturn(0);
        when(contentMapper.selectImagesByContentIds(anyLong())).thenReturn(new ArrayList<>());

        service.recommend(query("hot", null, 2, null, 0));

        // hot 保持既有 ZSET 热度路径，与画像流互不干涉
        verifyNoInteractions(recommendRerankService);
    }

    // ------------------------- 非法 scene -------------------------

    @Test
    void 非法scene_抛场景参数异常_语义不变() {
        ContentFailedException exception = assertThrows(ContentFailedException.class,
                () -> service.recommend(query("trending", null, 2, null, 0)));

        assertEquals("场景参数异常", exception.getMessage());
    }
}
