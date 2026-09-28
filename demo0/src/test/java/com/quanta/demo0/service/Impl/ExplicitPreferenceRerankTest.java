package com.quanta.demo0.service.Impl;

import com.quanta.demo0.constant.RedisConstants;
import com.quanta.demo0.content.entity.Content;
import com.quanta.demo0.mapper.ContentMapper;
import com.quanta.demo0.feed.properties.RecommendProperties;
import com.quanta.demo0.service.UserProfileService;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 推荐重排显式项：强负反馈只降权、不删候选；无行为冷启动也能响应明确偏好。 */
class ExplicitPreferenceRerankTest {
    @Test
    @SuppressWarnings("unchecked")
    void negativePreferenceDemotesHotContentWithoutFilteringItOut() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ZSetOperations<String, String> zset = mock(ZSetOperations.class);
        SetOperations<String, String> sets = mock(SetOperations.class);
        when(redis.opsForZSet()).thenReturn(zset);
        when(redis.opsForSet()).thenReturn(sets);
        when(zset.reverseRange(anyString(), eq(0L), anyLong())).thenReturn(Set.of("1", "2"));
        when(sets.members(anyString())).thenReturn(Set.of());
        ContentMapper mapper = mock(ContentMapper.class);
        Content hotBasketball = Content.builder().contentId(1L).contentType(1).tags("[\"basketball\"]")
                .auditStatus(1).isDeleted(0).liked(100).collectCount(0).commentCount(0)
                .createTime(LocalDateTime.now().minusHours(1)).build();
        Content coldFootball = Content.builder().contentId(2L).contentType(1).tags("[\"football\"]")
                .auditStatus(1).isDeleted(0).liked(0).collectCount(0).commentCount(0)
                .createTime(hotBasketball.getCreateTime()).build();
        when(mapper.selectBatchIds(anyList())).thenReturn(List.of(hotBasketball, coldFootball));
        UserProfileService profile = new UserProfileServiceImpl(mapper, redis);
        var hashes = mock(org.springframework.data.redis.core.HashOperations.class);
        when(redis.opsForHash()).thenReturn(hashes);
        when(hashes.entries(RedisConstants.USER_PROFILE_KEY + 123L)).thenReturn(Map.of());
        when(hashes.entries("user:profile-explicit:123")).thenReturn(Map.of("basketball", "-1.25", "__version", "3"));
        RecommendRerankServiceImpl rerank = new RecommendRerankServiceImpl(redis, mapper, profile, new RecommendProperties());
        assertThat(rerank.rerank(123L, null, 10).contents()).extracting(Content::getContentId).containsExactly(2L, 1L);
    }
}
