package com.quanta.demo0.service.Impl;

import cn.hutool.core.util.BooleanUtil;
import com.quanta.demo0.dto.FollowFeedQueryDTO;
import com.quanta.demo0.entity.*;

import com.quanta.demo0.enums.AuditStatus;
import com.quanta.demo0.enums.NotificationType;
import com.quanta.demo0.exception.FollowException;
import com.quanta.demo0.mapper.ContentMapper;
import com.quanta.demo0.mapper.FollowMapper;
import com.quanta.demo0.mapper.UserMapper;
import com.quanta.demo0.mq.message.NotificationEventMessage;
import com.quanta.demo0.result.ScrollResult;
import com.quanta.demo0.service.FollowService;
import com.quanta.demo0.service.OutboxEventService;
import com.quanta.demo0.vo.ContentVO;
import com.quanta.demo0.vo.FollowResultVO;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static com.quanta.demo0.constant.RedisConstants.*;

/**
 * 关注关系服务实现类。
 *
 * 核心职责：
 * 1. 处理关注/取关、共同关注、关注列表等社交关系能力；
 * 2. 维护 Redis 关注集合与数据库关系表的一致性；
 * 3. 负责关注流回填与滚动读取，支撑“关注页”内容分发。
 *
 * 设计说明：
 * - 关注动作落库后通过缓存与回填策略优化读取性能；
 * - 关键链路使用事务，避免关系状态与计数数据不一致。
 */
@Service
@Slf4j
public class FollowServiceImpl implements FollowService {
    private static final String USER_AGGREGATE_TYPE = "USER";

    /** 关注/回填时，每个被关注用户最多写入关注流的帖子数 */
    private static final int FOLLOW_FEED_BACKFILL_PER_USER = 50;
    @Autowired
    private FollowMapper followMapper;
    @Autowired
    private StringRedisTemplate stringRedisTemplate;
    @Autowired
    private ContentMapper contentMapper;
    @Autowired
    private UserMapper userMapper;
    @Autowired
    private OutboxEventService outboxEventService;

    @Transactional
    @Override
    public FollowResultVO follow(Long followUserId, boolean targetFollowed) {
        //1.参数校验
        if (followUserId == null) {
            throw new FollowException("参数不能为空");
        }
        //2.获取当前用户id
        Long userId = BaseContext.getCurrentId();
        //不能关注自己
        if (userId.equals(followUserId)) {
            throw new FollowException("不能关注自己");

        }

        String key = FOLLOWED_KEY + userId;

        //3.以数据库为准查询关注状态，并锁定当前关系
        Follow followExist = followMapper.selectExistForUpdate(userId, followUserId);
        boolean changed = false;
        boolean isFollowed = targetFollowed;

        if (targetFollowed) {
            if (followExist == null) {
                //第一次关注，插入
                Follow follow = Follow.builder()
                        .userId(userId)
                        .followUserId(followUserId)
                        .createTime(LocalDateTime.now())
                        .updateTime(LocalDateTime.now())
                        .isDeleted(0)
                        .build();
                int rows = followMapper.insert(follow);
                if (rows == 1) {
                    changed = true;
                }
            } else if (followExist.getIsDeleted() == 1) {
                //恢复关注
                Follow follow = Follow.builder()
                        .id(followExist.getId())
                        .userId(userId)
                        .followUserId(followUserId)
                        .updateTime(LocalDateTime.now())
                        .isDeleted(0)
                        .build();
                boolean isSuccess = followMapper.update(follow);
                if (!isSuccess) {
                    throw new FollowException("恢复关注失败");
                }
                changed = true;
            }
        } else if (followExist != null && followExist.getIsDeleted() == 0) {
            //取消关注
            Follow follow = Follow.builder()
                    .userId(userId)
                    .followUserId(followUserId)
                    .updateTime(LocalDateTime.now())
                    .isDeleted(1)
                    .build();
            boolean isSuccess = followMapper.update(follow);
            if (!isSuccess) {
                throw new FollowException("取消关注失败");
            }
            changed = true;
        }

        // 只有本次真正新增关注关系，才在当前事务中创建通知 Outbox。
        if (changed && isFollowed) {
            NotificationEventMessage followNotification = NotificationEventMessage.builder()
                    .recipientUserId(followUserId)
                    .actorUserId(userId)
                    .type(NotificationType.USER_FOLLOW.getCode())
                    .content("关注了你")
                    .payload(Map.of())
                    .build();
            outboxEventService.createNotificationEvent(followNotification, USER_AGGREGATE_TYPE, followUserId);
        }

        //事务提交后操作redis缓存
        if (changed && TransactionSynchronizationManager.isActualTransactionActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    try {
                        if (isFollowed) {
                            stringRedisTemplate.opsForSet().add(key, followUserId.toString());
                            backfillFollowFeedForUser(userId, followUserId);
                            // 维护粉丝排行 ZSET（被关注者的粉丝数 +1）
                            stringRedisTemplate.opsForZSet().incrementScore(USER_FOLLOWER_RANK_KEY, followUserId.toString(), 1);
                        } else {
                            stringRedisTemplate.opsForSet().remove(key, followUserId.toString());
                            // 维护粉丝排行 ZSET（被关注者的粉丝数 -1）
                            stringRedisTemplate.opsForZSet().incrementScore(USER_FOLLOWER_RANK_KEY, followUserId.toString(), -1);
                            // 清理已取关用户的帖子
                            clearUnfollowedUserPosts(userId, followUserId);

                        }

                    } catch (Exception e) {
                        log.error("关注缓存同步失败，businessType=USER_FOLLOW, userId={}, targetId={}, targetState={}",
                                userId, followUserId, isFollowed, e);
                      //  throw new FollowException("写入redisSET失败");
                    }
                }

            });
        }
        //4.返回结果
        return FollowResultVO.builder()
                .isFollowed(isFollowed)
                .build();

    }


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
        List<Content> contents = contentMapper.selectBatchIds(ids);

        //7.过滤内容类型
        if (followFeedQueryDTO.getContentType() != null) {
           contents=contents.stream().
                    filter(content ->
                            content.getContentType().equals(followFeedQueryDTO.getContentType()))
                    .toList();
        }

        //8.查询用户信息，处理点赞，收藏高亮
      List<Long> userIds= contents.stream()
                .map(Content::getPublishUserId)
              .distinct()
                .toList();

        List<UserAuthInfo> userAuthInfos =userIds==null||userIds.isEmpty()?new ArrayList<>():
                userMapper.selectUserAuthInfoByIds(userIds);
        Map<Long,UserAuthInfo>userAuthInfoMap=userAuthInfos.stream()
                .collect(Collectors.toMap(
                        UserAuthInfo::getUserId,
                        userAuthInfo -> userAuthInfo,
                                (v1,v2)->v1
                ));
        contents.forEach(this::isContentLiked);
        contents.forEach(this::isContentCollected);
        //9。转换为vo
        List<ContentVO> contentVOList = contents.stream()
                .map(content -> {
                            UserAuthInfo userAuthInfo=userAuthInfoMap
                                    .getOrDefault(content.getPublishUserId()
                                            ,new UserAuthInfo());
                            return convertContentToVO(content, userAuthInfo);
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
        List<Long> followerIds = followMapper.selectFollowerIds(publishUserId);
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
        List<Long> followerIds = followMapper.selectFollowerIds(publishUserId);

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

    private ContentVO convertContentToVO(Content content, UserAuthInfo userInfo) {
        List<ContentImage> contentImages = contentMapper.selectImagesByContentIds(content.getContentId());
        // 处理图片列表：如果为 null 则返回空列表，否则提取图片 URL 并过滤空字符串
        List<String> imageUrls = contentImages == null ? new ArrayList<>() :
                contentImages.stream()
                        .map(ContentImage::getImageUrl)
                        .filter(StringUtils::isNotBlank)
                        .collect(Collectors.toList());

        return ContentVO.builder()
                .contentId(content.getContentId())
                .contentType(content.getContentType())
                .title(content.getTitle())
                .content(content.getContent())
                .liked(content.getLiked()==null?0:content.getLiked())
                .commentCount(content.getCommentCount()==null?0:content.getCommentCount())
                .collectCount(content.getCollectCount()==null?0:content.getCollectCount())
                .publishUserId(content.getPublishUserId())
                .avatarUrl(userInfo.getAvatarUrl())           // 用户头像
                .nickName(userInfo.getNickName())          // 用户昵称
                .quantaDepartment(userInfo.getQuantaDepartment()) // 用户部门
                .quantaBatch(userInfo.getQuantaBatch())          // 用户届数
                .auditStatus(content.getAuditStatus())
                .createTime(content.getCreateTime())
                .images(imageUrls)
                .isLiked(BooleanUtil.isTrue(content.getIsLiked()))
                .isCollected(BooleanUtil.isTrue(content.getIsCollected()))
                .build();
    }





    private void isContentCollected(Content content) {
        Long userId = BaseContext.getCurrentId();
        if (userId == null) {
            content.setIsCollected(false);
            return;
        }
        String key = CONTENT_COLLECT_KEY + content.getContentId();
        Double score = stringRedisTemplate.opsForZSet().score(key, userId.toString());
        if (score != null) {
            content.setIsCollected(true);
            return;
        }
        boolean collectedInDb = contentMapper.countContentCollect(content.getContentId(), userId) > 0;
        content.setIsCollected(collectedInDb);
        if (collectedInDb) {
            stringRedisTemplate.opsForZSet().add(key, userId.toString(), System.currentTimeMillis());
        }
    }
    private void isContentLiked(Content content) {
        Long userId = BaseContext.getCurrentId();
        if (userId == null) {
            content.setIsLiked(false);
            return;
        }
        String key = CONTENT_LIKED_KEY + content.getContentId();
        Double score = stringRedisTemplate.opsForZSet().score(key, userId.toString());
        if (score != null) {
            content.setIsLiked(true);
            return;
        }
        boolean likedInDb = contentMapper.countContentLiked(content.getContentId(), userId) > 0;
        content.setIsLiked(likedInDb);
        if (likedInDb) {
            stringRedisTemplate.opsForZSet().add(key, userId.toString(), System.currentTimeMillis());
        }
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
        List<Long> fromDb = followMapper.selectFollowUserIds(followerId);
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
        List<Content> contents = contentMapper.selectApprovedByPublishUserId(
                followedUserId, FOLLOW_FEED_BACKFILL_PER_USER);
        if (contents == null || contents.isEmpty()) {
            return;
        }
        for (Content content : contents) {
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
        List<Content> contents = contentMapper.selectApprovedByPublishUserId(unfollowedUserId, 1000);
        if (contents == null || contents.isEmpty()) {
            return;
        }
        // 从关注流中删除这些帖子
        for (Content content : contents) {
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
    public void reconcileContentFeed(Long contentId, Long fallbackPublishUserId, Integer fallbackContentType, Long fallbackCreateTime) {
        Content current = contentMapper.selectById(contentId);

        if (current != null && AuditStatus.APPROVED.getCode().equals(current.getAuditStatus())) {
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
