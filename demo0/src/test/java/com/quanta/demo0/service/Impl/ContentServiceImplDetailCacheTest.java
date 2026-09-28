package com.quanta.demo0.service.Impl;

import com.quanta.demo0.platform.security.context.BaseContext;
import com.quanta.demo0.interaction.entity.BrowseHistory;
import com.quanta.demo0.user.vo.UserAuthInfoVO;
import com.quanta.demo0.content.enums.ContentDetailState;
import com.quanta.demo0.mapper.BrowseHistoryMapper;
import com.quanta.demo0.mapper.ContentMapper;
import com.quanta.demo0.service.AuthorProfileCache;
import com.quanta.demo0.service.ContentDetailCacheService;
import com.quanta.demo0.content.vo.ContentDetailCacheEntry;
import com.quanta.demo0.content.vo.ContentDetailSnapshot;
import com.quanta.demo0.content.vo.ContentVO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ContentServiceImplDetailCacheTest {

    private static final Long USER_ID = 9L;
    private static final Long CONTENT_ID = 31L;

    private ContentDetailCacheService detailCacheService;
    private AuthorProfileCache authorProfileCache;
    private ContentMapper contentMapper;
    private BrowseHistoryMapper browseHistoryMapper;
    private ContentServiceImpl service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        detailCacheService = mock(ContentDetailCacheService.class);
        authorProfileCache = mock(AuthorProfileCache.class);
        contentMapper = mock(ContentMapper.class);
        browseHistoryMapper = mock(BrowseHistoryMapper.class);
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        ZSetOperations<String, String> zSetOperations = mock(ZSetOperations.class);
        when(redisTemplate.opsForZSet()).thenReturn(zSetOperations);
        when(zSetOperations.score(anyString(), anyString())).thenReturn(null);

        service = new ContentServiceImpl();
        ReflectionTestUtils.setField(service, "contentDetailCacheService", detailCacheService);
        ReflectionTestUtils.setField(service, "contentDetailDataLoader", mock(ContentDetailDataLoader.class));
        ReflectionTestUtils.setField(service, "authorProfileCache", authorProfileCache);
        ReflectionTestUtils.setField(service, "contentMapper", contentMapper);
        ReflectionTestUtils.setField(service, "browseHistoryMapper", browseHistoryMapper);
        ReflectionTestUtils.setField(service, "stringRedisTemplate", redisTemplate);
        BaseContext.setCurrentId(USER_ID);

        ContentDetailSnapshot snapshot = new ContentDetailSnapshot(
                CONTENT_ID,
                1,
                "缓存标题",
                "缓存正文",
                7L,
                1,
                LocalDateTime.of(2026, 9, 20, 12, 0),
                8,
                3,
                2,
                List.of("https://img/1.png")
        );
        when(detailCacheService.getOrLoad(any(), any()))
                .thenReturn(new ContentDetailCacheEntry(ContentDetailState.FOUND, snapshot));
        when(authorProfileCache.get(7L)).thenReturn(UserAuthInfoVO.builder()
                .userId(7L)
                .nickName("作者")
                .avatarUrl("https://avatar")
                .quantaDepartment("计算机")
                .quantaBatch("2023")
                .build());
        when(contentMapper.countContentLiked(CONTENT_ID, USER_ID)).thenReturn(1);
        when(contentMapper.countContentCollect(CONTENT_ID, USER_ID)).thenReturn(0);
    }

    @AfterEach
    void tearDown() {
        BaseContext.removeCurrentId();
    }

    @Test
    void composesCachedSnapshotWithPerViewerStateAndBrowseHistory() {
        ContentVO result = service.getContentDetail(CONTENT_ID);

        assertEquals("缓存标题", result.getTitle());
        assertEquals("作者", result.getNickName());
        assertEquals(List.of("https://img/1.png"), result.getImages());
        assertTrue(result.getIsLiked());
        assertFalse(result.getIsCollected());
        verify(browseHistoryMapper).insertOrUpdateBrowseHistory(any(BrowseHistory.class));
        verify(contentMapper, never()).selectById(CONTENT_ID);
        verify(contentMapper, never()).selectImagesByContentIds(CONTENT_ID);
    }

    @Test
    void missingAuthorKeepsExistingEmptyAuthorFallback() {
        when(authorProfileCache.get(7L)).thenReturn(null);

        ContentVO result = service.getContentDetail(CONTENT_ID);

        assertEquals("缓存标题", result.getTitle());
        assertEquals(null, result.getNickName());
        verify(browseHistoryMapper).insertOrUpdateBrowseHistory(any(BrowseHistory.class));
    }
}
