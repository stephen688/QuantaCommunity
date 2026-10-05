package com.quanta.demo0.interaction.service.impl;

import com.quanta.demo0.content.exception.ContentFailedException;
import com.quanta.demo0.content.service.ContentCounterService;
import com.quanta.demo0.content.service.ContentDetailCacheInvalidator;
import com.quanta.demo0.content.vo.ContentSnapshotVO;
import com.quanta.demo0.interaction.entity.ContentCollect;
import com.quanta.demo0.interaction.entity.ContentLiked;
import com.quanta.demo0.interaction.mapper.ContentInteractionMapper;
import com.quanta.demo0.interaction.service.ContentInteractionService;
import com.quanta.demo0.interaction.vo.CollectResultVO;
import com.quanta.demo0.interaction.vo.LikeResultVO;
import com.quanta.demo0.moderation.enums.ModerationTargetType;
import com.quanta.demo0.notification.enums.NotificationType;
import com.quanta.demo0.notification.mq.message.NotificationEventMessage;
import com.quanta.demo0.notification.mq.producer.NotificationEventProducer;
import com.quanta.demo0.feed.mq.producer.FeedEventProducer;
import com.quanta.demo0.search.mq.producer.SearchEventProducer;
import com.quanta.demo0.platform.security.context.BaseContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;
import java.util.Map;

import static com.quanta.demo0.platform.redis.constant.RedisConstants.CONTENT_COLLECT_KEY;
import static com.quanta.demo0.platform.redis.constant.RedisConstants.CONTENT_LIKED_KEY;

@Service
@Slf4j
@RequiredArgsConstructor
public class ContentInteractionServiceImpl implements ContentInteractionService {

    private final ContentInteractionMapper contentInteractionMapper;
    private final ContentCounterService contentCounterService;
    private final NotificationEventProducer notificationEventProducer;
    private final FeedEventProducer feedEventProducer;
    private final SearchEventProducer searchEventProducer;
    private final ContentDetailCacheInvalidator contentDetailCacheInvalidator;
    private final StringRedisTemplate stringRedisTemplate;

    /**
     * 点赞内容。
     */
    @Override
    @Transactional
    public LikeResultVO likeContent(Long contentId, boolean targetLiked) {
        // ① 检查内容是否存在
        if (contentId == null) {
            throw new ContentFailedException("contentId不能为空");
        }
        // ② 检查用户是否已点赞
        ContentSnapshotVO content = contentCounterService.getContentSnapshot(contentId);
        if (content == null) {
            throw new ContentFailedException("内容不存在");
        }

        Long userId = BaseContext.getCurrentId();
        boolean changed = false;// 是否有变更

        if (targetLiked) {
            // ③ 点赞内容
            ContentLiked contentLiked = ContentLiked.builder()
                    .contentId(contentId)
                    .userId(userId)
                    .createTime(LocalDateTime.now())
                    .build();
            // ④ 插入点赞记录
            if (contentInteractionMapper.insertContentLiked(contentLiked) == 1) {
                if (contentCounterService.changeLikedCount(contentId, 1) != 1) {
                    throw new ContentFailedException("点赞失败");
                }
                changed = true;// 点赞成功
            }
        } else if (contentInteractionMapper.deleteContentLikedByUser(contentId, userId) == 1) {
            if (contentCounterService.changeLikedCount(contentId, -1) != 1) {
                throw new ContentFailedException("取消点赞失败");
            }
            changed = true;
        }

        // ⑤ 发送点赞通知
               if (changed && targetLiked && !content.getPublishUserId().equals(userId)) {
            NotificationEventMessage notification = NotificationEventMessage.builder()
                    .recipientUserId(content.getPublishUserId())
                    .actorUserId(userId)
                    .type(NotificationType.LIKE_CONTENT.getCode())
                    .content("点赞了你的内容")
                    .payload(Map.of("contentId", contentId))
                    .build();
            notificationEventProducer.createNotificationEvent(notification, ModerationTargetType.CONTENT.name(), contentId);
            feedEventProducer.createUserBehaviorEvent(userId, contentId, "LIKE");
        }

        if (changed) {
            String triggerType = targetLiked ? "LIKE" : "UNLIKE";
            //
            feedEventProducer.createHotScoreRecalculateEvent(contentId, triggerType);
            searchEventProducer.createSearchReconcileEvent(ModerationTargetType.CONTENT.name(), contentId, triggerType);
            contentDetailCacheInvalidator.evictAfterCommit(contentId, triggerType);
            synchronizeCacheAfterCommit(CONTENT_LIKED_KEY + contentId, userId, targetLiked, "CONTENT_LIKE");
        }

        ContentSnapshotVO updated = contentCounterService.getContentSnapshot(contentId);
        int likedCount = updated == null || updated.getLikedCount() == null ? 0 : updated.getLikedCount();
        return LikeResultVO.builder().likedCount(likedCount).isLiked(targetLiked).build();
    }

    @Override
    @Transactional
    public CollectResultVO collect(Long contentId, boolean targetCollected) {
        if (contentId == null) {
            throw new ContentFailedException("参数错误");
        }
        ContentSnapshotVO content = contentCounterService.getContentSnapshot(contentId);
        if (content == null) {
            throw new ContentFailedException("内容不存在");
        }
        Long userId = BaseContext.getCurrentId();
        boolean changed = false;

        if (targetCollected) {
            ContentCollect contentCollect = ContentCollect.builder()
                    .contentId(contentId)
                    .userId(userId)
                    .createTime(LocalDateTime.now())
                    .build();
            if (contentInteractionMapper.insertCollect(contentCollect) == 1) {
                if (contentCounterService.changeCollectCount(contentId, 1) != 1) {
                    throw new ContentFailedException("收藏失败");
                }
                changed = true;
            }
        } else if (contentInteractionMapper.deleteCollect(contentId, userId) == 1) {
            if (contentCounterService.changeCollectCount(contentId, -1) != 1) {
                throw new ContentFailedException("取消收藏失败");
            }
            changed = true;
        }

        if (changed) {
            if (targetCollected) {
                feedEventProducer.createUserBehaviorEvent(userId, contentId, "COLLECT");
            }
            String triggerType = targetCollected ? "COLLECT" : "UNCOLLECT";
            feedEventProducer.createHotScoreRecalculateEvent(contentId, triggerType);
            searchEventProducer.createSearchReconcileEvent(ModerationTargetType.CONTENT.name(), contentId, triggerType);
            contentDetailCacheInvalidator.evictAfterCommit(contentId, triggerType);
            synchronizeCacheAfterCommit(CONTENT_COLLECT_KEY + contentId, userId, targetCollected, "CONTENT_COLLECT");
        }

        ContentSnapshotVO updated = contentCounterService.getContentSnapshot(contentId);
        int collectCount = updated == null || updated.getCollectCount() == null ? 0 : updated.getCollectCount();
        return CollectResultVO.builder().collectCount(collectCount).isCollect(targetCollected).build();
    }

    @Override
    public void deleteByContentId(Long contentId) {
        contentInteractionMapper.deleteContentLikedByContentId(contentId);
        contentInteractionMapper.deleteContentCollectByContentId(contentId);
    }

    @Override
    public boolean isContentLiked(Long contentId, Long userId) {
        if (userId == null) {
            return false;
        }
        String key = CONTENT_LIKED_KEY + contentId;
        Double score = stringRedisTemplate.opsForZSet().score(key, userId.toString());
        if (score != null) {
            return true;
        }
        boolean liked = contentInteractionMapper.countContentLiked(contentId, userId) > 0;
        if (liked) {
            stringRedisTemplate.opsForZSet().add(key, userId.toString(), System.currentTimeMillis());
        }
        return liked;
    }

    @Override
    public boolean isContentCollected(Long contentId, Long userId) {
        if (userId == null) {
            return false;
        }
        String key = CONTENT_COLLECT_KEY + contentId;
        Double score = stringRedisTemplate.opsForZSet().score(key, userId.toString());
        if (score != null) {
            return true;
        }
        boolean collected = contentInteractionMapper.countContentCollect(contentId, userId) > 0;
        if (collected) {
            stringRedisTemplate.opsForZSet().add(key, userId.toString(), System.currentTimeMillis());
        }
        return collected;
    }
    // ⑥ 事务提交后同步互动缓存
    /**
     * 事务提交后同步互动缓存。
     * 【契约】事务内调用 → 注册 afterCommit 回调，事务回滚则不会失效（正确：数据没变）；
     * 无事务调用 → 立即失效。不抛异常、无返回值——失效丢失由缓存 TTL 兜底。
     */
       private void synchronizeCacheAfterCommit(String key, Long userId, boolean targetState, String businessType) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    //添加或删除互动缓存记录：key的userId为key，当前时间为score
                    if (targetState) {
                        stringRedisTemplate.opsForZSet().add(key, userId.toString(), System.currentTimeMillis());
                    } else {
                        stringRedisTemplate.opsForZSet().remove(key, userId.toString());
                    }
                } catch (Exception e) {
                    log.error("互动缓存同步失败，businessType={}, userId={}, targetState={}",
                            businessType, userId, targetState, e);
                }
            }
        });
    }
}
