package com.quanta.demo0.service.Impl;

import com.quanta.demo0.entity.UserAuthInfo;
import com.quanta.demo0.mapper.UserMapper;
import com.quanta.demo0.platform.redis.properties.ReadPathCacheProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthorProfileCacheImplTest {

    @Mock
    private UserMapper userMapper;

    private ReadPathCacheProperties properties;
    private AuthorProfileCacheImpl cache;

    @BeforeEach
    void setUp() {
        properties = new ReadPathCacheProperties();
        properties.getAuthor().setMaximumSize(10L);
        properties.getAuthor().setTtlSeconds(180L);
        cache = new AuthorProfileCacheImpl(userMapper, properties);
    }

    @Test
    void getReturnsCachedAuthorWithoutQueryingMapperAgain() {
        UserAuthInfo author = author(7L, "Ada");
        when(userMapper.selectUserAuthInfoById(7L)).thenReturn(author);

        assertThat(cache.get(7L)).isEqualTo(author);
        assertThat(cache.get(7L)).isEqualTo(author);

        verify(userMapper, times(1)).selectUserAuthInfoById(7L);
    }

    @Test
    void getLoadsOneMissingAuthorAndCachesIt() {
        UserAuthInfo author = author(7L, "Ada");
        when(userMapper.selectUserAuthInfoById(7L)).thenReturn(author);

        assertThat(cache.get(7L)).isEqualTo(author);

        verify(userMapper).selectUserAuthInfoById(7L);
    }

    @Test
    void getAllWithAllAuthorsCachedDoesNotQueryMapper() {
        UserAuthInfo first = author(7L, "Ada");
        UserAuthInfo second = author(8L, "Grace");
        when(userMapper.selectUserAuthInfoById(7L)).thenReturn(first);
        when(userMapper.selectUserAuthInfoById(8L)).thenReturn(second);
        cache.get(7L);
        cache.get(8L);

        Map<Long, UserAuthInfo> result = cache.getAll(List.of(8L, 7L));

        assertThat(result.keySet()).containsExactly(8L, 7L);
        assertThat(result).containsEntry(8L, second).containsEntry(7L, first);
        verify(userMapper, times(1)).selectUserAuthInfoById(7L);
        verify(userMapper, times(1)).selectUserAuthInfoById(8L);
        verify(userMapper, never()).selectUserAuthInfoByIds(anyList());
    }

    @Test
    void getAllDeduplicatesIdsAndLoadsMissesWithOneBatchQueryInFirstSeenOrder() {
        UserAuthInfo first = author(7L, "Ada");
        UserAuthInfo second = author(8L, "Grace");
        UserAuthInfo third = author(9L, "Lin");
        when(userMapper.selectUserAuthInfoById(8L)).thenReturn(second);
        cache.get(8L);
        when(userMapper.selectUserAuthInfoByIds(List.of(7L, 9L)))
                .thenReturn(List.of(first, third, second));

        Map<Long, UserAuthInfo> result = cache.getAll(Arrays.asList(8L, 7L, 8L, null, 9L));

        assertThat(result.keySet()).containsExactly(8L, 7L, 9L);
        assertThat(result).containsEntry(8L, second)
                .containsEntry(7L, first)
                .containsEntry(9L, third);
        verify(userMapper).selectUserAuthInfoByIds(List.of(7L, 9L));
        verify(userMapper, times(1)).selectUserAuthInfoById(eq(8L));
        verify(userMapper, never()).selectUserAuthInfoById(eq(7L));
        verify(userMapper, never()).selectUserAuthInfoById(eq(9L));
    }

    @Test
    void getAllOmitsMissingRowsWithoutCreatingFakeAuthorsOrDuplicateKeyFailure() {
        UserAuthInfo author = author(7L, "Ada");
        when(userMapper.selectUserAuthInfoByIds(List.of(7L, 8L, 9L)))
                .thenReturn(Arrays.asList(author, author(7L, "duplicate"), null));

        Map<Long, UserAuthInfo> result = cache.getAll(List.of(7L, 8L, 9L));

        assertThat(result).containsOnlyKeys(7L);
        assertThat(result.get(7L)).isEqualTo(author);
        assertThat(result.get(8L)).isNull();
        assertThat(result.get(9L)).isNull();
    }

    @Test
    void evictForcesTheNextReadToQueryMapperAgain() {
        when(userMapper.selectUserAuthInfoById(7L))
                .thenReturn(author(7L, "before"), author(7L, "after"));

        assertThat(cache.get(7L).getNickName()).isEqualTo("before");
        cache.evict(7L);

        assertThat(cache.get(7L).getNickName()).isEqualTo("after");
        verify(userMapper, times(2)).selectUserAuthInfoById(7L);
    }

    @Test
    void loaderFailureIsPropagatedAndNotCached() {
        when(userMapper.selectUserAuthInfoById(7L))
                .thenThrow(new IllegalStateException("mysql unavailable"))
                .thenReturn(author(7L, "Ada"));

        assertThatThrownBy(() -> cache.get(7L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("mysql unavailable");
        assertThat(cache.get(7L)).isEqualTo(author(7L, "Ada"));

        verify(userMapper, times(2)).selectUserAuthInfoById(7L);
    }

    @Test
    void nullIdsDoNotQueryMapperOrEnterResult() {
        assertThat(cache.get(null)).isNull();
        assertThat(cache.getAll(Arrays.asList(null, null))).isEmpty();

        verifyNoUserLookup();
    }

    private void verifyNoUserLookup() {
        verify(userMapper, never()).selectUserAuthInfoById(org.mockito.ArgumentMatchers.any());
        verify(userMapper, never()).selectUserAuthInfoByIds(anyList());
    }

    private UserAuthInfo author(Long userId, String nickName) {
        return UserAuthInfo.builder()
                .userId(userId)
                .nickName(nickName)
                .avatarUrl("avatar-" + userId)
                .quantaDepartment("department-" + userId)
                .quantaBatch("batch-" + userId)
                .authStatus(1)
                .accountStatus(0)
                .build();
    }
}
