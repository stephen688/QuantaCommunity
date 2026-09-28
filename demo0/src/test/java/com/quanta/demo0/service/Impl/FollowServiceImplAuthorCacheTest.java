package com.quanta.demo0.service.Impl;
import com.quanta.demo0.platform.redis.properties.ReadPathCacheProperties;


import com.quanta.demo0.dto.FollowFeedQueryDTO;
import com.quanta.demo0.platform.security.context.BaseContext;
import com.quanta.demo0.entity.Content;
import com.quanta.demo0.entity.UserAuthInfo;
import com.quanta.demo0.mapper.ContentMapper;
import com.quanta.demo0.mapper.FollowMapper;
import com.quanta.demo0.mapper.UserMapper;
import com.quanta.demo0.platform.common.result.ScrollResult;
import com.quanta.demo0.service.AuthorProfileCache;
import com.quanta.demo0.service.OutboxEventService;
import com.quanta.demo0.vo.ContentVO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.DefaultTypedTuple;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.quanta.demo0.constant.RedisConstants.FEED_ALL_KEY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class FollowServiceImplAuthorCacheTest {

    private static final Long VIEWER_ID = 7L;

    private StringRedisTemplate redisTemplate;
    private ContentMapper contentMapper;
    private UserMapper userMapper;
    private FollowServiceImpl service;

    @BeforeEach
    void setUp() {
        BaseContext.setCurrentId(VIEWER_ID);
        redisTemplate = mock(StringRedisTemplate.class, org.mockito.Answers.RETURNS_DEEP_STUBS);
        contentMapper = mock(ContentMapper.class);
        userMapper = mock(UserMapper.class);
        FollowMapper followMapper = mock(FollowMapper.class);
        OutboxEventService outboxEventService = mock(OutboxEventService.class);

        when(redisTemplate.opsForZSet().zCard(FEED_ALL_KEY + VIEWER_ID)).thenReturn(1L);
        when(redisTemplate.opsForZSet().reverseRangeByScoreWithScores(
                eq(FEED_ALL_KEY + VIEWER_ID), eq(0D), anyDouble(), eq(0L), eq(3L)
        )).thenReturn(feedEntries(101L, 102L));
        when(contentMapper.selectImagesByContentIds(anyLong())).thenReturn(Collections.emptyList());
        when(contentMapper.countContentLiked(anyLong(), eq(VIEWER_ID))).thenReturn(0);
        when(contentMapper.countContentCollect(anyLong(), eq(VIEWER_ID))).thenReturn(0);

        service = new FollowServiceImpl();
        ReflectionTestUtils.setField(service, "followMapper", followMapper);
        ReflectionTestUtils.setField(service, "stringRedisTemplate", redisTemplate);
        ReflectionTestUtils.setField(service, "contentMapper", contentMapper);
        ReflectionTestUtils.setField(service, "outboxEventService", outboxEventService);
    }

    @AfterEach
    void tearDown() {
        BaseContext.removeCurrentId();
    }

    @Test
    void feedUsesAuthorCacheBatchWhenAllAuthorsAreAlreadyCached() {
        contentMapperReturns(contents(101L, 11L, 102L, 12L));
        AuthorProfileCache authorCache = mock(AuthorProfileCache.class);
        when(authorCache.getAll(List.of(11L, 12L)))
                .thenReturn(Map.of(11L, author(11L, "Ada"), 12L, author(12L, "Grace")));
        ReflectionTestUtils.setField(service, "authorProfileCache", authorCache);

        ScrollResult result = service.getFollowFeed(query());

        verify(authorCache).getAll(List.of(11L, 12L));
        assertThat(contentViews(result)).extracting(ContentVO::getNickName)
                .containsExactly("Ada", "Grace");
        verifyNoInteractions(userMapper);
    }

    @Test
    void feedPartiallyMissingAuthorsStillUsesOneBatchCacheLookup() {
        contentMapperReturns(contents(101L, 11L, 102L, 12L, 103L, 11L));
        when(redisTemplate.opsForZSet().reverseRangeByScoreWithScores(
                eq(FEED_ALL_KEY + VIEWER_ID), eq(0D), anyDouble(), eq(0L), eq(4L)
        )).thenReturn(feedEntries(101L, 102L, 103L));

        AuthorProfileCache authorCache = new AuthorProfileCacheImpl(userMapper, newReadPathProperties());
        ReflectionTestUtils.setField(service, "authorProfileCache", authorCache);
        when(userMapper.selectUserAuthInfoByIds(List.of(11L, 12L)))
                .thenReturn(List.of(author(11L, "Ada"), author(12L, "Grace")));

        ScrollResult result = service.getFollowFeed(query(3));

        verify(userMapper).selectUserAuthInfoByIds(List.of(11L, 12L));
        assertThat(contentViews(result)).extracting(ContentVO::getNickName)
                .containsExactly("Ada", "Grace", "Ada");
    }

    @Test
    void repeatedAuthorIsLoadedOnceAndMissingAuthorKeepsEmptyFallback() {
        contentMapperReturns(contents(101L, 11L, 102L, 11L, 103L, 99L));
        when(redisTemplate.opsForZSet().reverseRangeByScoreWithScores(
                eq(FEED_ALL_KEY + VIEWER_ID), eq(0L), anyLong(), eq(0L), eq(4L)
        )).thenReturn(feedEntries(101L, 102L, 103L));

        AuthorProfileCache authorCache = new AuthorProfileCacheImpl(userMapper, newReadPathProperties());
        ReflectionTestUtils.setField(service, "authorProfileCache", authorCache);
        when(userMapper.selectUserAuthInfoByIds(List.of(11L, 99L)))
                .thenReturn(List.of(author(11L, "Ada")));

        ScrollResult result = service.getFollowFeed(query(3));

        verify(userMapper).selectUserAuthInfoByIds(List.of(11L, 99L));
        assertThat(contentViews(result)).extracting(ContentVO::getNickName)
                .containsExactly("Ada", "Ada", null);
    }

    private FollowFeedQueryDTO query() {
        return query(2);
    }

    private FollowFeedQueryDTO query(int pageSize) {
        return FollowFeedQueryDTO.builder()
                .offset(0)
                .pageSize(pageSize)
                .build();
    }

    private void contentMapperReturns(List<Content> contents) {
        when(contentMapper.selectBatchIds(any())).thenReturn(contents);
    }

    private List<Content> contents(Object... values) {
        java.util.ArrayList<Content> contents = new java.util.ArrayList<>();
        for (int i = 0; i < values.length; i += 2) {
            contents.add(Content.builder()
                    .contentId((Long) values[i])
                    .publishUserId((Long) values[i + 1])
                    .contentType(1)
                    .title("title-" + values[i])
                    .content("content-" + values[i])
                    .build());
        }
        return contents;
    }

    private Set<ZSetOperations.TypedTuple<String>> feedEntries(Long... contentIds) {
        LinkedHashSet<ZSetOperations.TypedTuple<String>> entries = new LinkedHashSet<>();
        long score = contentIds.length;
        for (Long contentId : contentIds) {
            entries.add(new DefaultTypedTuple<>(contentId.toString(), (double) score--));
        }
        return entries;
    }

    @SuppressWarnings("unchecked")
    private List<ContentVO> contentViews(ScrollResult result) {
        return (List<ContentVO>) result.getList();
    }

    private UserAuthInfo author(Long userId, String name) {
        return UserAuthInfo.builder()
                .userId(userId)
                .nickName(name)
                .avatarUrl("avatar-" + userId)
                .build();
    }

    private com.quanta.demo0.platform.redis.properties.ReadPathCacheProperties newReadPathProperties() {
        com.quanta.demo0.platform.redis.properties.ReadPathCacheProperties properties =
                new com.quanta.demo0.platform.redis.properties.ReadPathCacheProperties();
        properties.getAuthor().setMaximumSize(10L);
        properties.getAuthor().setTtlSeconds(180L);
        return properties;
    }
}
