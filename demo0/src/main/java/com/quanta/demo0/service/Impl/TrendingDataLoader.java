package com.quanta.demo0.service.Impl;

import com.quanta.demo0.constant.RedisConstants;
import com.quanta.demo0.content.entity.Content;
import com.quanta.demo0.user.vo.UserAuthInfoVO;
import com.quanta.demo0.mapper.ContentMapper;
import com.quanta.demo0.search.mapper.SearchMapper;
import com.quanta.demo0.mapper.UserMapper;
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
    private final ContentMapper contentMapper;
    private final UserMapper userMapper;

    public TrendingDataLoader(
            SearchMapper searchMapper,
            StringRedisTemplate stringRedisTemplate,
            SearchTrendingProperties searchTrendingProperties,
            ContentMapper contentMapper,
            UserMapper userMapper
    ) {
        this.searchMapper = searchMapper;
        this.stringRedisTemplate = stringRedisTemplate;
        this.searchTrendingProperties = searchTrendingProperties;
        this.contentMapper = contentMapper;
        this.userMapper = userMapper;
    }

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

    private List<String> loadKeywords(int limit) {
        List<String> keywords = searchMapper.selectHotKeywords(limit);
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

    private List<HotQuestionVO> loadQuestions(int limit) {
        if (limit == 0) {
            return Collections.emptyList();
        }

        List<Long> rankedIds = readRankedIds(
                RedisConstants.RECOMMEND_HOT_ALL_KEY,
                limit
        );
        List<Content> rankedContents = rankedIds.isEmpty()
                ? Collections.emptyList()
                : contentMapper.selectBatchIds(rankedIds);
        Map<Long, Content> contentsById = indexContents(rankedContents);

        List<HotQuestionVO> questions = new ArrayList<>(limit);
        Set<Long> selectedIds = new HashSet<>();
        for (Long rankedId : rankedIds) {
            Content content = contentsById.get(rankedId);
            if (isApprovedContent(content) && selectedIds.add(rankedId)) {
                questions.add(toHotQuestion(content));
                if (questions.size() == limit) {
                    return questions;
                }
            }
        }

        // 查询完整 limit，避免 DB TopN 前几项与排行结果重复时补不满。
        List<Content> fallbackContents = contentMapper.selectTopLikedContents(limit);
        if (fallbackContents == null) {
            return questions;
        }
        for (Content content : fallbackContents) {
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
                : userMapper.selectUserAuthInfoByIds(rankedIds);
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
        List<UserAuthInfoVO> fallbackUsers = userMapper.selectTopFollowedUsers(limit);
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

    private Map<Long, Content> indexContents(List<Content> contents) {
        Map<Long, Content> indexed = new HashMap<>();
        if (contents == null) {
            return indexed;
        }
        for (Content content : contents) {
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

    private boolean isApprovedContent(Content content) {
        return content != null
                && content.getContentId() != null
                && Integer.valueOf(0).equals(content.getIsDeleted())
                && Integer.valueOf(1).equals(content.getAuditStatus());
    }

    private boolean isAvailableAlumni(UserAuthInfoVO user) {
        Integer accountStatus = user == null ? null : user.getAccountStatus();
        return user != null
                && user.getUserId() != null
                && (accountStatus == null || accountStatus == 0);
    }

    private HotQuestionVO toHotQuestion(Content content) {
        return HotQuestionVO.builder()
                .contentId(content.getContentId())
                .title(content.getTitle())
                .liked(content.getLiked())
                .build();
    }

    private HotAlumniVO toHotAlumni(UserAuthInfoVO user) {
        return HotAlumniVO.builder()
                .userId(user.getUserId())
                .nickName(user.getNickName())
                .avatarUrl(user.getAvatarUrl())
                .build();
    }

    private int limitOf(Integer limit) {
        return limit == null ? 0 : Math.max(limit, 0);
    }
}
