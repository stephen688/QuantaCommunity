package com.quanta.demo0.follow.service.impl;

import com.quanta.demo0.follow.entity.Follow;
import com.quanta.demo0.follow.exception.FollowException;
import com.quanta.demo0.follow.mapper.FollowMapper;
import com.quanta.demo0.follow.service.FollowCommandService;
import com.quanta.demo0.follow.vo.FollowResultVO;
import com.quanta.demo0.feed.service.FollowFeedService;
import com.quanta.demo0.notification.enums.NotificationType;
import com.quanta.demo0.notification.mq.message.NotificationEventMessage;
import com.quanta.demo0.platform.mq.service.OutboxEventService;
import com.quanta.demo0.platform.security.context.BaseContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;
import java.util.Map;

import static com.quanta.demo0.platform.redis.constant.RedisConstants.FOLLOWED_KEY;
import static com.quanta.demo0.platform.redis.constant.RedisConstants.USER_FOLLOWER_RANK_KEY;

/**
 * 关注关系命令服务。
 */
@Service
@Slf4j
public class FollowCommandServiceImpl implements FollowCommandService {
    private static final String USER_AGGREGATE_TYPE = "USER";

    @Autowired
    private FollowMapper followMapper;
    @Autowired
    private StringRedisTemplate stringRedisTemplate;
    @Autowired
    private FollowFeedService followFeedService;
    @Autowired
    private OutboxEventService outboxEventService;

    @Transactional
    @Override
    public FollowResultVO follow(Long followUserId, boolean targetFollowed) {
        if (followUserId == null) {
            throw new FollowException("参数不能为空");
        }
        Long userId = BaseContext.getCurrentId();
        if (userId.equals(followUserId)) {
            throw new FollowException("不能关注自己");
        }

        String key = FOLLOWED_KEY + userId;
        Follow followExist = followMapper.selectExistForUpdate(userId, followUserId);
        boolean changed = false;

        if (targetFollowed) {
            if (followExist == null) {
                Follow follow = Follow.builder()
                        .userId(userId)
                        .followUserId(followUserId)
                        .createTime(LocalDateTime.now())
                        .updateTime(LocalDateTime.now())
                        .isDeleted(0)
                        .build();
                changed = followMapper.insert(follow) == 1;
            } else if (followExist.getIsDeleted() == 1) {
                Follow follow = Follow.builder()
                        .id(followExist.getId())
                        .userId(userId)
                        .followUserId(followUserId)
                        .updateTime(LocalDateTime.now())
                        .isDeleted(0)
                        .build();
                if (!followMapper.update(follow)) {
                    throw new FollowException("恢复关注失败");
                }
                changed = true;
            }
        } else if (followExist != null && followExist.getIsDeleted() == 0) {
            Follow follow = Follow.builder()
                    .userId(userId)
                    .followUserId(followUserId)
                    .updateTime(LocalDateTime.now())
                    .isDeleted(1)
                    .build();
            if (!followMapper.update(follow)) {
                throw new FollowException("取消关注失败");
            }
            changed = true;
        }

        if (changed && targetFollowed) {
            NotificationEventMessage followNotification = NotificationEventMessage.builder()
                    .recipientUserId(followUserId)
                    .actorUserId(userId)
                    .type(NotificationType.USER_FOLLOW.getCode())
                    .content("关注了你")
                    .payload(Map.of())
                    .build();
            outboxEventService.createNotificationEvent(followNotification, USER_AGGREGATE_TYPE, followUserId);
        }

        if (changed && TransactionSynchronizationManager.isActualTransactionActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    try {
                        if (targetFollowed) {
                            stringRedisTemplate.opsForSet().add(key, followUserId.toString());
                            followFeedService.syncFollowChange(userId, followUserId, true);
                            stringRedisTemplate.opsForZSet().incrementScore(
                                    USER_FOLLOWER_RANK_KEY, followUserId.toString(), 1);
                        } else {
                            stringRedisTemplate.opsForSet().remove(key, followUserId.toString());
                            stringRedisTemplate.opsForZSet().incrementScore(
                                    USER_FOLLOWER_RANK_KEY, followUserId.toString(), -1);
                            followFeedService.syncFollowChange(userId, followUserId, false);
                        }
                    } catch (Exception e) {
                        log.error("关注缓存同步失败，businessType=USER_FOLLOW, userId={}, targetId={}, targetState={}",
                                userId, followUserId, targetFollowed, e);
                    }
                }
            });
        }

        return FollowResultVO.builder()
                .isFollowed(targetFollowed)
                .build();
    }
}
