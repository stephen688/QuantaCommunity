package com.quanta.demo0.feed.service.impl;

import com.quanta.demo0.feed.dto.FollowFeedQueryDTO;
import com.quanta.demo0.platform.security.context.BaseContext;

import com.quanta.demo0.follow.exception.FollowException;
import com.quanta.demo0.content.service.ContentQueryService;
import com.quanta.demo0.content.vo.ContentSnapshotVO;
import com.quanta.demo0.interaction.service.ContentInteractionService;
import com.quanta.demo0.follow.service.FollowQueryService;
import com.quanta.demo0.platform.common.result.ScrollResult;
import com.quanta.demo0.user.service.AuthorProfileCache;
import com.quanta.demo0.feed.service.FollowFeedService;
import com.quanta.demo0.content.vo.ContentVO;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.quanta.demo0.platform.redis.constant.RedisConstants.*;
import com.quanta.demo0.user.vo.UserAuthInfoVO;

/**
 * 关注关系服务实现类。
 *
 * 核心职责：
 * 1. 维护关注流 Redis 投影；
 * 2. 负责关注流回填与滚动读取，支撑“关注页”内容分发。
 *
 * 设计说明：
 * - 关注动作落库后通过缓存与回填策略优化读取性能；
 * - 关键链路使用事务，避免关系状态与计数数据不一致。
 */
@Service
@Slf4j
public class FollowFeedServiceImpl implements FollowFeedService {

    /** 关注/回填时，每个被关注用户最多写入关注流的帖子数 */
    private static final int FOLLOW_FEED_BACKFILL_PER_USER = 50;
    @Autowired
    private FollowQueryService followQueryService;
    @Autowired
    private StringRedisTemplate stringRedisTemplate;
    @Autowired
    private ContentQueryService contentQueryService;
    @Autowired
    private ContentInteractionService contentInteractionService;
    @Autowired
    private AuthorProfileCache authorProfileCache;
    /**
     * 关注推送页面，获取关注用户的动态
     *
     * @param followFeedQueryDTO
     * @return
     */
    @Override
    public ScrollResult getFollowFeed(FollowFeedQueryDTO followFeedQueryDTO) {
        //1.参数校验
        if (followFeedQueryDTO == null) {
            throw new FollowException("参数不能为空");
        }
        if (followFeedQueryDTO.getContentType() != null &&
                followFeedQueryDTO.getContentType() != 1 &&
                followFeedQueryDTO.getContentType() != 2) {
            throw new FollowException("内容类型不合法");
        }
        //设置参数
        int pageSize = (followFeedQueryDTO.getPageSize() == null || followFeedQueryDTO.getPageSize() <= 0) ? 10 : followFeedQueryDTO.getPageSize();
        int limit = pageSize + 1; // 多查一条，判断是否有更多数据
        long maxTime = followFeedQueryDTO.getLastId() != null ? followFeedQueryDTO.getLastId() : System.currentTimeMillis();
        long minScore= 0L;
        int offset = followFeedQueryDTO.getOffset() != null ? followFeedQueryDTO.getOffset() : 0;
        //获取当前用户id
        Long userId = BaseContext.getCurrentId();

        // 关注流为空时，从 DB 回填已关注用户的历史帖子（解决「已关注但无动态」）
        ensureFollowFeedSynced(userId);

        //2确定redisKey,查询idsWithScores
        String key = resolveFeedKey(userId, followFeedQueryDTO.getContentType());

        Set<ZSetOperations.TypedTuple<String>> idsWithScores = stringRedisTemplate.opsForZSet().reverseRangeByScoreWithScores(key, minScore, maxTime, offset, limit);

        //3.处理空结果
        if (idsWithScores == null || idsWithScores.isEmpty()) {
            return ScrollResult
                    .builder()
                    .list(new ArrayList<>())
                    .minScore(null)
                    .offset(0)
                    .hasMore(false)
                    .build();

        }
        //4.提取所有ids
        List<Long> ids = new ArrayList<>(idsWithScores.size());
        for (ZSetOperations.TypedTuple<String> typedTuple : idsWithScores) {
            if (typedTuple.getValue() == null || typedTuple.getScore() == null) {
                continue;
            }
            ids.add(Long.valueOf(typedTuple.getValue()));
        }
        //5.判断是否有下一页
        boolean hasMore = ids.size() > pageSize;
        if (hasMore) {
            ids = ids.subList(0, pageSize);
        }

        //6.计算下一页的offset与lastId(minTime)
        minScore = 0L;
        int os = 1;
        int count = 0;
        for (ZSetOperations.TypedTuple<String> typedTuple : idsWithScores) {
            if (typedTuple == null || typedTuple.getScore() == null) {
                continue;
            }
            count++;
            if (count > pageSize) {
                break;

            }
            long time = typedTuple.getScore().longValue();
            if ( minScore == time) {
                os++;
            } else {
                minScore = time;
                os = 1;
            }
        }
        //6.查看内容详情，
        List<ContentSnapshotVO> contents = contentQueryService.getContentSnapshots(ids);

        //7.过滤内容类型
        if (followFeedQueryDTO.getContentType() != null) {
           contents=contents.stream().
                    filter(content ->
                            content.getContentType().equals(followFeedQueryDTO.getContentType()))
                    .toList();
        }

        Map<Long, List<String>> imageUrlsByContentId = contents.isEmpty()
                ? Collections.emptyMap()
                : contentQueryService.getContentImageUrlsBatch(
                        contents.stream().map(ContentSnapshotVO::getContentId).toList());
        if (imageUrlsByContentId == null) {
            imageUrlsByContentId = Collections.emptyMap();
        }
        Map<Long, List<String>> imageMap = imageUrlsByContentId;

        //8.查询用户信息，处理点赞，收藏高亮
      List<Long> userIds= contents.stream()
                .map(ContentSnapshotVO::getPublishUserId)
              .distinct()
                .toList();

        Map<Long, UserAuthInfoVO> userAuthInfoMap = authorProfileCache.getAll(userIds);
        //9。转换为vo
        List<ContentVO> contentVOList = contents.stream()
                .map(content -> {
                            UserAuthInfoVO userAuthInfo=userAuthInfoMap
                                    .getOrDefault(content.getPublishUserId()
                                            ,new UserAuthInfoVO());
                            return convertContentToVO(content, userAuthInfo,
                                    imageMap.getOrDefault(content.getContentId(), List.of()));
                        }).toList();
        //10.封装返回
        return ScrollResult
                .builder()
                .list(contentVOList)
                .minScore(Double.valueOf(minScore))
                .offset(os)
                .hasMore(hasMore)
                .build();
    }

    @Override
    public void pushToFollowersFeed(Long createTime, Integer contentType, Long publishUserId, Long contentId) {
        //1.查询粉丝ids
        List<Long> followerIds = followQueryService.getFollowerIds(publishUserId);
        if (followerIds == null || followerIds.isEmpty()) {
            log.info("没有粉丝，不需要推送");
            return;


        }



        //2.推送到粉丝的feed中
        for (Long followerId : followerIds) {
            //2.推送到全部关注的feed中
            String allKey = resolveFeedKey(followerId, null);
            stringRedisTemplate.opsForZSet().add(allKey, contentId.toString(), createTime);

            //3.推送到对应内容类型的feed中
            String key = resolveFeedKey(followerId, contentType);
            stringRedisTemplate.opsForZSet().add(key, contentId.toString(), createTime);
        }
    }

    @Override
    public void removeFeedFromFollowers(Long contentId, Integer contentType, Long publishUserId) {
        // 1. 查询发布者的所有粉丝
        List<Long> followerIds = followQueryService.getFollowerIds(publishUserId);

        if (followerIds == null || followerIds.isEmpty()) {
            log.info("用户 {} 没有粉丝，跳过 Feed 删除", publishUserId);
            return;
        }

        // 2. 遍历粉丝，从每个粉丝的 Feed 流中删除该内容
        for (Long followerId : followerIds) {
            // 从"全部关注"池中删除
            String allKey = resolveFeedKey(followerId, null);
            stringRedisTemplate.opsForZSet().remove(allKey, contentId.toString());

            // 从"分类池"中删除
            String categoryKey = resolveFeedKey(followerId, contentType);
            stringRedisTemplate.opsForZSet().remove(categoryKey, contentId.toString());
        }

        log.info("Feed 删除完成：contentId={}, 粉丝数量={}", contentId, followerIds.size());
    }

    private ContentVO convertContentToVO(ContentSnapshotVO content,
                                         UserAuthInfoVO userInfo,
                                         List<String> imageUrls) {
        return ContentVO.builder()
                .contentId(content.getContentId())
                .contentType(content.getContentType())
                .title(content.getTitle())
                .content(content.getContent())
                .liked(content.getLikedCount() == null ? 0 : content.getLikedCount())
                .commentCount(content.getCommentCount() == null ? 0 : content.getCommentCount())
                .collectCount(content.getCollectCount() == null ? 0 : content.getCollectCount())
                .publishUserId(content.getPublishUserId())
                .avatarUrl(userInfo.getAvatarUrl())
                .nickName(userInfo.getNickName())
                .quantaDepartment(userInfo.getQuantaDepartment())
                .quantaBatch(userInfo.getQuantaBatch())
                .auditStatus(content.getAuditStatus())
                .createTime(content.getCreateTime())
                .images(imageUrls)
                .isLiked(isContentLiked(content.getContentId()))
                .isCollected(isContentCollected(content.getContentId()))
                .build();
    }

    private boolean isContentCollected(Long contentId) {
        Long userId = BaseContext.getCurrentId();
        if (userId == null) {
            return false;
        }
        String key = CONTENT_COLLECT_KEY + contentId;
        Double score = stringRedisTemplate.opsForZSet().score(key, userId.toString());
        if (score != null) {
            return true;
        }
        boolean collectedInDb = contentInteractionService.isContentCollected(contentId, userId);
        if (collectedInDb) {
            stringRedisTemplate.opsForZSet().add(key, userId.toString(), System.currentTimeMillis());
        }
        return collectedInDb;
    }

    private boolean isContentLiked(Long contentId) {
        Long userId = BaseContext.getCurrentId();
        if (userId == null) {
            return false;
        }
        String key = CONTENT_LIKED_KEY + contentId;
        Double score = stringRedisTemplate.opsForZSet().score(key, userId.toString());
        if (score != null) {
            return true;
        }
        boolean likedInDb = contentInteractionService.isContentLiked(contentId, userId);
        if (likedInDb) {
            stringRedisTemplate.opsForZSet().add(key, userId.toString(), System.currentTimeMillis());
        }
        return likedInDb;
    }

    private String resolveFeedKey(Long userId, Integer contentType) {
        if (contentType == null) {
            return FEED_ALL_KEY + userId;
        } else if (contentType == 1) {
            return FEED_LIFE_KEY+ userId ;
        } else if (contentType == 2) {
            return FEED_PROFESSIONAL_KEY + userId ;
        } else {
            throw new FollowException("内容类型不合法");

        }
    }

    /**
     * 当关注流 ZSET 为空但用户已有关注关系时，回填历史帖子到 Redis。
     */
    private void ensureFollowFeedSynced(Long followerId) {
        if (followerId == null) {
            return;
        }
        String allKey = resolveFeedKey(followerId, null);
        Long size = stringRedisTemplate.opsForZSet().zCard(allKey);
        if (size != null && size > 0) {
            return;
        }
        List<Long> followedIds = resolveFollowedUserIds(followerId);
        if (followedIds.isEmpty()) {
            return;
        }
        for (Long followedUserId : followedIds) {
            backfillFollowFeedForUser(followerId, followedUserId);
        }
        log.info("关注流回填完成: followerId={}, followedCount={}", followerId, followedIds.size());
    }

    private List<Long> resolveFollowedUserIds(Long followerId) {
        String followKey = FOLLOWED_KEY + followerId;
        Set<String> members = stringRedisTemplate.opsForSet().members(followKey);
        if (members != null && !members.isEmpty()) {
            List<Long> ids = new ArrayList<>(members.size());
            for (String member : members) {
                if (StringUtils.isBlank(member)) {
                    continue;
                }
                try {
                    ids.add(Long.valueOf(member));
                } catch (NumberFormatException ignored) {
                    log.warn("无效的关注用户 ID: {}", member);
                }
            }
            if (!ids.isEmpty()) {
                return ids;
            }
        }
        List<Long> fromDb = followQueryService.getFollowedUserIds(followerId);
        if (fromDb == null || fromDb.isEmpty()) {
            return Collections.emptyList();
        }
        for (Long id : fromDb) {
            stringRedisTemplate.opsForSet().add(followKey, id.toString());
        }
        return fromDb;
    }

    private void backfillFollowFeedForUser(Long followerId, Long followedUserId) {
        if (followerId == null || followedUserId == null) {
            return;
        }
        List<ContentSnapshotVO> contents = contentQueryService.getApprovedContentSnapshotsByAuthor(
                followedUserId, FOLLOW_FEED_BACKFILL_PER_USER);
        if (contents == null || contents.isEmpty()) {
            return;
        }
        for (ContentSnapshotVO content : contents) {
            if (content == null || content.getContentId() == null || content.getCreateTime() == null) {
                continue;
            }
            long score = content.getCreateTime()
                    .atZone(ZoneId.systemDefault())
                    .toInstant()
                    .toEpochMilli();
            String contentId = content.getContentId().toString();
            stringRedisTemplate.opsForZSet().add(resolveFeedKey(followerId, null), contentId, score);
            Integer contentType = content.getContentType();
            if (contentType != null) {
                stringRedisTemplate.opsForZSet().add(resolveFeedKey(followerId, contentType), contentId, score);
            }
        }
    }

    /**
     * 取关时清理已取关用户的帖子
     * 从当前用户的关注流中删除已取关用户发布的所有帖子
     */
    private void clearUnfollowedUserPosts(Long followerId, Long unfollowedUserId) {
        if (followerId == null || unfollowedUserId == null) {
            return;
        }
        // 查询已取关用户发布的所有帖子
        List<ContentSnapshotVO> contents = contentQueryService.getApprovedContentSnapshotsByAuthor(unfollowedUserId, 1000);
        if (contents == null || contents.isEmpty()) {
            return;
        }
        // 从关注流中删除这些帖子
        for (ContentSnapshotVO content : contents) {
            if (content == null || content.getContentId() == null) {
                continue;
            }
            String contentId = content.getContentId().toString();
            // 从"全部关注"池中删除
            stringRedisTemplate.opsForZSet().remove(resolveFeedKey(followerId, null), contentId);
            // 从"分类池"中删除
            Integer contentType = content.getContentType();
            if (contentType != null) {
                stringRedisTemplate.opsForZSet().remove(resolveFeedKey(followerId, contentType), contentId);
            }
        }
        log.info("取关清理完成: followerId={}, unfollowedUserId={}, 清理帖子数={}", followerId, unfollowedUserId, contents.size());
    }


    @Override
    public void syncFollowChange(Long followerId, Long followedUserId, boolean followed) {
        if (followerId == null || followedUserId == null) {
            return;
        }
        if (followed) {
            backfillFollowFeedForUser(followerId, followedUserId);
        } else {
            clearUnfollowedUserPosts(followerId, followedUserId);
        }
    }



    @Override
    public void reconcileContentFeed(Long contentId, Long fallbackPublishUserId, Integer fallbackContentType, Long fallbackCreateTime) {
        ContentSnapshotVO current = contentQueryService.getContentSnapshots(List.of(contentId)).stream()
                .findFirst()
                .orElse(null);

        if (current != null) {
            // 当前仍然审核通过，无论收到新增还是旧删除事件，最终都应该存在于 Feed。
            long createTime = current.getCreateTime() != null
                    ? current.getCreateTime().atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
                    : fallbackCreateTime;

            pushToFollowersFeed(createTime, current.getContentType(), current.getPublishUserId(), contentId);
            return;
        }

        // 当前已删除、驳回或不存在，无论收到什么旧事件，最终都必须从 Feed 删除。
        Long publishUserId = current != null ? current.getPublishUserId() : fallbackPublishUserId;
        Integer contentType = current != null ? current.getContentType() : fallbackContentType;
        removeFeedFromFollowers(contentId, contentType, publishUserId);
    }
}
