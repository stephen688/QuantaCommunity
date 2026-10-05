package com.quanta.demo0.feed.service.impl;

import com.github.pagehelper.Page;
import com.quanta.demo0.content.exception.ContentFailedException;
import com.quanta.demo0.content.service.ContentQueryService;
import com.quanta.demo0.content.vo.ContentSnapshotVO;
import com.quanta.demo0.content.vo.ContentVO;
import com.quanta.demo0.feed.dto.RecommendQueryDTO;
import com.quanta.demo0.feed.dto.RecommendVisitor;
import com.quanta.demo0.feed.properties.RecommendProperties;
import com.quanta.demo0.feed.service.RecommendSessionService;
import com.quanta.demo0.feed.vo.RecommendPageVO;
import com.quanta.demo0.feed.vo.RecommendSessionPage;
import com.quanta.demo0.feed.service.FeedQueryService;
import com.quanta.demo0.feed.service.HotContentService;
import com.quanta.demo0.feed.service.RecommendRerankService;
import com.quanta.demo0.interaction.service.ContentInteractionService;
import com.quanta.demo0.platform.common.result.ScrollResult;
import com.quanta.demo0.platform.security.context.BaseContext;
import com.quanta.demo0.user.service.AuthorProfileCache;
import com.quanta.demo0.user.vo.UserAuthInfoVO;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 推荐流查询实现。
 *
 * <p>推荐候选由画像重排服务提供，内容详情统一通过 ContentQueryService 快照端口读取，
 * 因而本服务不直接依赖 ContentMapper。</p>
 */
@Service
@RequiredArgsConstructor
public class FeedQueryServiceImpl implements FeedQueryService {

    private final RecommendRerankService recommendRerankService;
    private final ContentQueryService contentQueryService;
    private final AuthorProfileCache authorProfileCache;
    private final StringRedisTemplate stringRedisTemplate;
    private final HotContentService hotContentService;
    private final ContentInteractionService contentInteractionService;
    private final RecommendSessionService recommendSessionService;
    private final RecommendProperties recommendProperties;

    /** 新协议只改变推荐发现路径，旧接口及热度路径继续使用原实现。 */
    @Override
    public RecommendPageVO recommend(RecommendQueryDTO query, RecommendVisitor visitor) {
        boolean isHot = query != null && "hot".equals(query.getScene());
        boolean newSession = query != null && query.getFeedSessionId() != null;
        if (!isHot && newSession && recommendProperties.getDiscovery().isEnabled()) {
            String scene = query.getScene();
            if (scene != null && !"recommend".equals(scene) && !"latest".equals(scene)) {
                throw new ContentFailedException("场景参数异常");
            }
            RecommendSessionPage page = recommendSessionService.page(visitor, query);
            return RecommendPageVO.builder()
                    .list(assembleSnapshotVOs(page.getContents(), visitor.userId()))
                    .minScore(null).offset(0).hasMore(page.getHasMore())
                    .feedSessionId(page.getFeedSessionId()).nextCursor(page.getNextCursor())
                    .recommendationState(page.getRecommendationState()).canRevisit(page.getCanRevisit())
                    .build();
        }
        ScrollResult legacy = recommend(query);
        @SuppressWarnings("unchecked")
        List<ContentVO> contents = (List<ContentVO>) legacy.getList();
        return RecommendPageVO.builder().list(contents).minScore(legacy.getMinScore())
                .offset(legacy.getOffset()).hasMore(legacy.getHasMore()).build();
    }

    @Override
    @Transactional
    public ScrollResult recommend(RecommendQueryDTO recommendQueryDTO) {
        // 处理空参数
               if (recommendQueryDTO == null) {
            recommendQueryDTO = RecommendQueryDTO.builder().build();
        }
         // 处理内容类型参数
        Integer contentType = recommendQueryDTO.getContentType();
        if (contentType != null && contentType != 1 && contentType != 2) {
            throw new ContentFailedException("内容类型必须为 1 或者 2或者 null");
        }
        // 处理分页参数
        int pageSize = recommendQueryDTO.getPageSize() == null || recommendQueryDTO.getPageSize() <= 0
                ? 5 : recommendQueryDTO.getPageSize();
        String scene = recommendQueryDTO.getScene();
        if (scene == null || "latest".equals(scene) || "recommend".equals(scene)) {
            return recommendByProfileFlow(contentType, pageSize);
        }
        // 处理场景参数，只支持 hot 场景
        if (!"hot".equals(scene)) {
            throw new ContentFailedException("场景参数异常");
        }
        // 处理 hot 场景参数
        return recommendByHotFlow(recommendQueryDTO, pageSize);
    }

    private ScrollResult recommendByProfileFlow(Integer contentType, int pageSize) {
        RecommendRerankService.RerankResult rerankResult = recommendRerankService.rerank(
                BaseContext.getCurrentId(), contentType, pageSize);
        List<Long> ids = rerankResult.contents() == null ? Collections.emptyList() : rerankResult.contents().stream()
                .map(content -> content == null ? null : content.getContentId())
                .filter(Objects::nonNull)
                .toList();
        List<ContentSnapshotVO> snapshots = contentQueryService.getContentSnapshots(ids);
        return ScrollResult.builder()
                .list(assembleSnapshotVOs(snapshots))
                .minScore(null)
                .offset(0)
                .hasMore(!snapshots.isEmpty() && rerankResult.hasMore())
                .build();
    }

    private ScrollResult recommendByHotFlow(RecommendQueryDTO query, int pageSize) {
        int limit = pageSize * 3;
        double maxScore = query.getLastScore() == null ? Double.MAX_VALUE : query.getLastScore();
        int offset = query.getOffset() == null || query.getOffset() < 0 ? 0 : query.getOffset();
        String key = hotContentService.resolveRecommendHotKey(query.getContentType());
        LoopFetchResult fetchResult = loopFetchRecommendIds(key, pageSize, maxScore, offset, limit, 5);
        if (fetchResult.ids.isEmpty()) {
            return ScrollResult.builder().list(new ArrayList<>()).minScore(null).offset(0).hasMore(false).build();
        }
        List<ContentSnapshotVO> snapshots = contentQueryService.getContentSnapshots(fetchResult.ids);
        return ScrollResult.builder()
                .list(assembleSnapshotVOs(snapshots))
                .minScore(fetchResult.minScore)
                .offset(fetchResult.offset)
                .hasMore(fetchResult.hasMore)
                .build();
    }

    private List<ContentVO> assembleSnapshotVOs(List<ContentSnapshotVO> snapshots) {
        return assembleSnapshotVOs(snapshots, BaseContext.getCurrentId());
    }

    /** 内容VO不缓存，按本次可信主体重新补齐互动状态。 */
    private List<ContentVO> assembleSnapshotVOs(List<ContentSnapshotVO> snapshots, Long visitorUserId) {
        if (snapshots == null || snapshots.isEmpty()) {
            return new ArrayList<>();
        }
        List<Long> userIds = snapshots.stream().map(ContentSnapshotVO::getPublishUserId)
                .filter(Objects::nonNull).distinct().toList();
        Map<Long, UserAuthInfoVO> authors = authorProfileCache.getAll(userIds);
        if (authors == null) {
            authors = Collections.emptyMap();
        }
        Map<Long, UserAuthInfoVO> authorMap = authors;
        return snapshots.stream().map(snapshot -> {
            UserAuthInfoVO author = authorMap.getOrDefault(snapshot.getPublishUserId(), new UserAuthInfoVO());
            return ContentVO.builder()
                    .contentId(snapshot.getContentId())
                    .contentType(snapshot.getContentType())
                    .title(snapshot.getTitle())
                    .content(snapshot.getContent())
                    .publishUserId(snapshot.getPublishUserId())
                    .auditStatus(snapshot.getAuditStatus())
                    .createTime(snapshot.getCreateTime())
                    .liked(snapshot.getLikedCount() == null ? 0 : snapshot.getLikedCount())
                    .commentCount(snapshot.getCommentCount() == null ? 0 : snapshot.getCommentCount())
                    .collectCount(snapshot.getCollectCount() == null ? 0 : snapshot.getCollectCount())
                    .avatarUrl(author.getAvatarUrl())
                    .nickName(author.getNickName())
                    .quantaDepartment(author.getQuantaDepartment())
                    .quantaBatch(author.getQuantaBatch())
                    .isLiked(contentInteractionService.isContentLiked(
                            snapshot.getContentId(), visitorUserId))
                    .isCollected(contentInteractionService.isContentCollected(
                            snapshot.getContentId(), visitorUserId))
                    .build();
        }).toList();
    }

    private LoopFetchResult loopFetchRecommendIds(String key, int pageSize, double initMaxScore,
                                                   int initOffset, int perFetchLimit, int maxLoop) {
        Map<Long, Double> idScoreMap = new java.util.LinkedHashMap<>();
        double cursorScore = initMaxScore;
        int cursorOffset = initOffset;
        boolean sourceExhausted = false;
        int targetCount = pageSize + 1;
        for (int loop = 0; loop < maxLoop; loop++) {
            Set<ZSetOperations.TypedTuple<String>> tuples = stringRedisTemplate.opsForZSet()
                    .reverseRangeByScoreWithScores(key, 0, cursorScore, cursorOffset, perFetchLimit);
            if (tuples == null || tuples.isEmpty()) {
                sourceExhausted = true;
                break;
            }
            List<ZSetOperations.TypedTuple<String>> filtered = filterAndSort(tuples);
            for (ZSetOperations.TypedTuple<String> tuple : filtered) {
                idScoreMap.putIfAbsent(Long.valueOf(tuple.getValue()), tuple.getScore());
            }
            CursorResult next = nextCursorFromBatch(tuples, cursorScore, cursorOffset);
            cursorScore = next.minScore;
            cursorOffset = next.offset;
            if (idScoreMap.size() >= targetCount) {
                break;
            }
            if (tuples.size() < perFetchLimit) {
                sourceExhausted = true;
                break;
            }
        }
        List<Long> allIds = new ArrayList<>(idScoreMap.keySet());
        List<Long> finalIds = allIds.size() > pageSize ? new ArrayList<>(allIds.subList(0, pageSize)) : allIds;
        boolean hasMore = !finalIds.isEmpty() && (allIds.size() > pageSize || !sourceExhausted);
        double returnMinScore = cursorScore;
        int returnOffset = cursorOffset;
        if (!finalIds.isEmpty()) {
            Double lastScore = idScoreMap.get(finalIds.get(finalIds.size() - 1));
            if (lastScore != null) {
                returnMinScore = lastScore;
                returnOffset = 0;
                for (Long id : finalIds) {
                    if (Double.compare(idScoreMap.get(id), returnMinScore) == 0) {
                        returnOffset++;
                    }
                }
                if (Double.compare(returnMinScore, initMaxScore) == 0) {
                    returnOffset += initOffset;
                }
            }
        }
        return new LoopFetchResult(finalIds, returnMinScore, returnOffset, hasMore);
    }

    private List<ZSetOperations.TypedTuple<String>> filterAndSort(Set<ZSetOperations.TypedTuple<String>> tuples) {
        List<ZSetOperations.TypedTuple<String>> valid = new ArrayList<>();
        for (ZSetOperations.TypedTuple<String> tuple : tuples) {
            if (tuple == null || tuple.getValue() == null || tuple.getScore() == null) {
                continue;
            }
            try {
                Long.parseLong(tuple.getValue());
                valid.add(tuple);
            } catch (NumberFormatException ignored) {
                // Redis 中的脏 member 跳过，保持旧服务容错语义。
            }
        }
        valid.sort((left, right) -> {
            int score = Double.compare(right.getScore(), left.getScore());
            return score == 0 ? Long.compare(Long.parseLong(right.getValue()), Long.parseLong(left.getValue())) : score;
        });
        return valid;
    }

    private CursorResult nextCursorFromBatch(Set<ZSetOperations.TypedTuple<String>> batch,
                                             double currentMaxScore, int currentOffset) {
        if (batch == null || batch.isEmpty()) {
            return new CursorResult(currentMaxScore, currentOffset);
        }
        ZSetOperations.TypedTuple<String> lastValid = null;
        for (ZSetOperations.TypedTuple<String> tuple : batch) {
            if (tuple != null && tuple.getValue() != null && tuple.getScore() != null) {
                lastValid = tuple;
            }
        }
        if (lastValid == null) {
            return new CursorResult(currentMaxScore, currentOffset);
        }
        double newMaxScore = lastValid.getScore();
        int newOffset = 0;
        for (ZSetOperations.TypedTuple<String> tuple : batch) {
            if (tuple != null && tuple.getScore() != null && Double.compare(tuple.getScore(), newMaxScore) == 0) {
                newOffset++;
            }
        }
        if (Double.compare(newMaxScore, currentMaxScore) == 0) {
            newOffset += currentOffset;
        }
        return new CursorResult(newMaxScore, newOffset);
    }

    private record CursorResult(double minScore, int offset) {
    }

    private record LoopFetchResult(List<Long> ids, double minScore, int offset, boolean hasMore) {
    }
}
