package com.quanta.demo0.service.Impl;

import com.quanta.demo0.content.entity.Content;
import com.quanta.demo0.user.vo.UserAuthInfoVO;
import com.quanta.demo0.mapper.ContentMapper;
import com.quanta.demo0.mapper.SearchMapper;
import com.quanta.demo0.mapper.UserMapper;
import com.quanta.demo0.properties.SearchTrendingProperties;
import com.quanta.demo0.vo.SearchTrendingVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static com.quanta.demo0.constant.RedisConstants.RECOMMEND_HOT_ALL_KEY;
import static com.quanta.demo0.constant.RedisConstants.USER_FOLLOWER_RANK_KEY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 热榜数据构建器的 TDD 行为测试。 */
@ExtendWith(org.mockito.junit.jupiter.MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TrendingDataLoaderTest {

    @Mock
    private SearchMapper searchMapper;

    @Mock
    private ContentMapper contentMapper;

    @Mock
    private UserMapper userMapper;

    @Mock
    private StringRedisTemplate stringRedisTemplate;

    @Mock
    private ZSetOperations<String, String> zSetOperations;

    private SearchTrendingProperties properties;

    @BeforeEach
    void setUp() {
        properties = new SearchTrendingProperties();
        properties.setKeywordLimit(2);
        properties.setQuestionLimit(2);
        properties.setAlumniLimit(2);
        when(stringRedisTemplate.opsForZSet()).thenReturn(zSetOperations);
        stubEmptyRankings();
        when(contentMapper.selectTopLikedContents(anyInt())).thenReturn(Collections.emptyList());
        when(userMapper.selectTopFollowedUsers(anyInt())).thenReturn(Collections.emptyList());
    }

    @Test
    void defaultKeywordsStillRespectKeywordLimit() {
        when(searchMapper.selectHotKeywords(2)).thenReturn(Collections.emptyList());

        SearchTrendingVO result = load();

        assertThat(result.getHotKeywords())
                .containsExactly("Spring Boot", "考研复习")
                .hasSize(2);
        verify(searchMapper).selectHotKeywords(2);
    }

    @Test
    void keywordResultsAreAlsoCappedByKeywordLimit() {
        when(searchMapper.selectHotKeywords(2))
                .thenReturn(List.of("one", "two", "three"));

        SearchTrendingVO result = load();

        assertThat(result.getHotKeywords()).containsExactly("one", "two");
    }

    @Test
    void questionRankingOrderIsRestoredAfterBatchLookup() {
        properties.setQuestionLimit(3);
        when(zSetOperations.reverseRange(RECOMMEND_HOT_ALL_KEY, 0L, 5L))
                .thenReturn(orderedSet("30", "10", "20"));
        when(contentMapper.selectBatchIds(List.of(30L, 10L, 20L)))
                .thenReturn(List.of(content(10L, 0, 1), content(20L, 0, 1), content(30L, 0, 1)));

        SearchTrendingVO result = load();

        assertThat(result.getHotQuestions())
                .extracting(question -> question.getContentId())
                .containsExactly(30L, 10L, 20L);
    }

    @Test
    void invalidQuestionIdsAreSkippedAndDeletedOrUnapprovedQuestionsAreFiltered() {
        when(zSetOperations.reverseRange(RECOMMEND_HOT_ALL_KEY, 0L, 3L))
                .thenReturn(orderedSet("not-a-long", "1", "2", "3"));
        when(contentMapper.selectBatchIds(List.of(1L, 2L, 3L)))
                .thenReturn(List.of(
                        content(1L, 0, 1),
                        content(2L, 1, 1),
                        content(3L, 0, 0)
                ));

        SearchTrendingVO result = load();

        assertThat(result.getHotQuestions())
                .extracting(question -> question.getContentId())
                .containsExactly(1L);
    }

    @Test
    void questionRankingAndFallbackNeverExceedQuestionLimit() {
        properties.setQuestionLimit(2);
        when(zSetOperations.reverseRange(RECOMMEND_HOT_ALL_KEY, 0L, 3L))
                .thenReturn(orderedSet("1"));
        when(contentMapper.selectBatchIds(List.of(1L)))
                .thenReturn(List.of(content(1L, 0, 1)));
        when(contentMapper.selectTopLikedContents(2))
                .thenReturn(List.of(content(1L, 0, 1), content(2L, 0, 1), content(3L, 0, 1)));

        SearchTrendingVO result = load();

        assertThat(result.getHotQuestions())
                .extracting(question -> question.getContentId())
                .containsExactly(1L, 2L)
                .hasSize(2);
    }

    @Test
    void questionDatabaseFallbackDeduplicatesRankedItems() {
        properties.setQuestionLimit(3);
        when(zSetOperations.reverseRange(RECOMMEND_HOT_ALL_KEY, 0L, 5L))
                .thenReturn(orderedSet("1"));
        when(contentMapper.selectBatchIds(List.of(1L)))
                .thenReturn(List.of(content(1L, 0, 1)));
        when(contentMapper.selectTopLikedContents(3))
                .thenReturn(List.of(content(1L, 0, 1), content(2L, 0, 1), content(2L, 0, 1)));

        SearchTrendingVO result = load();

        assertThat(result.getHotQuestions())
                .extracting(question -> question.getContentId())
                .containsExactly(1L, 2L);
    }

    @Test
    void questionFallbackRequestsFullLimitSoRankedDuplicatesDoNotStarveResults() {
        properties.setQuestionLimit(3);
        when(zSetOperations.reverseRange(RECOMMEND_HOT_ALL_KEY, 0L, 5L))
                .thenReturn(orderedSet("1"));
        when(contentMapper.selectBatchIds(List.of(1L)))
                .thenReturn(List.of(content(1L, 0, 1)));
        when(contentMapper.selectTopLikedContents(3))
                .thenReturn(List.of(content(1L, 0, 1), content(2L, 0, 1), content(3L, 0, 1)));

        SearchTrendingVO result = load();

        assertThat(result.getHotQuestions())
                .extracting(question -> question.getContentId())
                .containsExactly(1L, 2L, 3L);
    }

    @Test
    void alumniRankingFiltersBannedUsersAndRespectsLimit() {
        properties.setAlumniLimit(2);
        when(zSetOperations.reverseRange(USER_FOLLOWER_RANK_KEY, 0L, 3L))
                .thenReturn(orderedSet("3", "1", "2"));
        when(userMapper.selectUserAuthInfoByIds(List.of(3L, 1L, 2L)))
                .thenReturn(List.of(
                        alumni(1L, 0),
                        alumni(2L, 1),
                        alumni(3L, 0)
                ));

        SearchTrendingVO result = load();

        assertThat(result.getHotAlumni())
                .extracting(alumni -> alumni.getUserId())
                .containsExactly(3L, 1L)
                .hasSize(2);
    }

    @Test
    void alumniDatabaseFallbackFillsMissingItemsWithoutDuplicates() {
        properties.setAlumniLimit(3);
        when(zSetOperations.reverseRange(USER_FOLLOWER_RANK_KEY, 0L, 5L))
                .thenReturn(orderedSet("7"));
        when(userMapper.selectUserAuthInfoByIds(List.of(7L)))
                .thenReturn(List.of(alumni(7L, 0)));
        when(userMapper.selectTopFollowedUsers(3))
                .thenReturn(List.of(alumni(7L, 0), alumni(8L, 0), alumni(8L, 0)));

        SearchTrendingVO result = load();

        assertThat(result.getHotAlumni())
                .extracting(alumni -> alumni.getUserId())
                .containsExactly(7L, 8L);
    }

    @Test
    void alumniFallbackRequestsFullLimitSoRankedDuplicatesDoNotStarveResults() {
        properties.setAlumniLimit(3);
        when(zSetOperations.reverseRange(USER_FOLLOWER_RANK_KEY, 0L, 5L))
                .thenReturn(orderedSet("7"));
        when(userMapper.selectUserAuthInfoByIds(List.of(7L)))
                .thenReturn(List.of(alumni(7L, 0)));
        when(userMapper.selectTopFollowedUsers(3))
                .thenReturn(List.of(alumni(7L, 0), alumni(8L, 0), alumni(9L, 0)));

        SearchTrendingVO result = load();

        assertThat(result.getHotAlumni())
                .extracting(alumni -> alumni.getUserId())
                .containsExactly(7L, 8L, 9L);
    }

    @Test
    void rankingRedisFailureFallsBackToDatabase() {
        when(zSetOperations.reverseRange(RECOMMEND_HOT_ALL_KEY, 0L, 3L))
                .thenThrow(new RedisConnectionFailureException("redis unavailable"));
        when(contentMapper.selectTopLikedContents(2))
                .thenReturn(List.of(content(9L, 0, 1), content(8L, 0, 1)));

        SearchTrendingVO result = load();

        assertThat(result.getHotQuestions())
                .extracting(question -> question.getContentId())
                .containsExactly(9L, 8L);
        verify(contentMapper, never()).selectBatchIds(eq(List.of()));
    }

    @Test
    void searchMapperFailureIsPropagated() {
        when(searchMapper.selectHotKeywords(2))
                .thenThrow(new IllegalStateException("mysql search failure"));

        assertThatThrownBy(this::load)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("mysql search failure");
    }

    @Test
    void contentMapperFailureIsPropagated() {
        when(zSetOperations.reverseRange(RECOMMEND_HOT_ALL_KEY, 0L, 3L))
                .thenReturn(orderedSet("11"));
        when(contentMapper.selectBatchIds(List.of(11L)))
                .thenThrow(new IllegalStateException("mysql content failure"));

        assertThatThrownBy(this::load)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("mysql content failure");
    }

    @Test
    void userMapperFailureIsPropagated() {
        when(zSetOperations.reverseRange(USER_FOLLOWER_RANK_KEY, 0L, 3L))
                .thenThrow(new RedisConnectionFailureException("redis unavailable"));
        when(userMapper.selectTopFollowedUsers(2))
                .thenThrow(new IllegalStateException("mysql user failure"));

        assertThatThrownBy(this::load)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("mysql user failure");
    }

    private void stubEmptyRankings() {
        when(zSetOperations.reverseRange(RECOMMEND_HOT_ALL_KEY, 0L, 3L))
                .thenReturn(Collections.emptySet());
        when(zSetOperations.reverseRange(USER_FOLLOWER_RANK_KEY, 0L, 3L))
                .thenReturn(Collections.emptySet());
        when(searchMapper.selectHotKeywords(2)).thenReturn(Collections.emptyList());
    }

    private Set<String> orderedSet(String... values) {
        return new LinkedHashSet<>(Arrays.asList(values));
    }

    private Content content(Long id, int deleted, int auditStatus) {
        return Content.builder()
                .contentId(id)
                .title("question-" + id)
                .liked(10)
                .isDeleted(deleted)
                .auditStatus(auditStatus)
                .build();
    }

    private UserAuthInfoVO alumni(Long id, int accountStatus) {
        return UserAuthInfoVO.builder()
                .userId(id)
                .nickName("alumni-" + id)
                .accountStatus(accountStatus)
                .build();
    }

    private SearchTrendingVO load() {
        return new TrendingDataLoader(
                searchMapper,
                stringRedisTemplate,
                properties,
                contentMapper,
                userMapper
        ).load();
    }
}
