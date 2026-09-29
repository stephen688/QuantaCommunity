package com.quanta.demo0.interaction.service.impl;

import com.quanta.demo0.answer.entity.QuestionAnswer;
import com.quanta.demo0.answer.service.AnswerCounterService;
import com.quanta.demo0.content.exception.ContentFailedException;
import com.quanta.demo0.interaction.entity.AnswerLiked;
import com.quanta.demo0.interaction.mapper.AnswerInteractionMapper;
import com.quanta.demo0.interaction.service.AnswerInteractionService;
import com.quanta.demo0.interaction.vo.LikeResultVO;
import com.quanta.demo0.mapper.QuestionMapper;
import com.quanta.demo0.moderation.enums.ModerationTargetType;
import com.quanta.demo0.notification.enums.NotificationType;
import com.quanta.demo0.notification.mq.message.NotificationEventMessage;
import com.quanta.demo0.platform.mq.service.OutboxEventService;
import com.quanta.demo0.platform.redis.constant.RedisConstants;
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

/** 回答点赞实现：明细、同步计数和 Outbox 在同一事务中完成。 */
@Service
@Slf4j
public class AnswerInteractionServiceImpl implements AnswerInteractionService {

    @Autowired
    private QuestionMapper questionMapper;
    @Autowired
    private AnswerInteractionMapper answerInteractionMapper;
    @Autowired
    private AnswerCounterService answerCounterService;
    @Autowired
    private StringRedisTemplate stringRedisTemplate;
    @Autowired
    private OutboxEventService outboxEventService;

    @Override
    @Transactional
    public LikeResultVO likeAnswer(Long answerId, boolean targetLiked) {
        if (answerId == null) {
            throw new ContentFailedException("answerId 不能为空");
        }
        QuestionAnswer answer = questionMapper.selectById(answerId);
        if (answer == null) {
            throw new ContentFailedException("回答不存在");
        }
        Long userId = BaseContext.getCurrentId();
        String key = RedisConstants.ANSWER_LIKED_KEY + answerId;
        boolean changed = false;
        if (targetLiked) {
            AnswerLiked answerLiked = AnswerLiked.builder()
                    .answerId(answerId)
                    .userId(userId)
                    .createTime(LocalDateTime.now())
                    .build();
            int inserted = answerInteractionMapper.insertAnswerLiked(answerLiked);
            if (inserted == 1) {
                if (answerCounterService.updateLikeCount(answerId, 1) != 1) {
                    throw new ContentFailedException("点赞失败");
                }
                changed = true;
            }
        } else {
            int deleted = answerInteractionMapper.deleteAnswerLikedByUser(answerId, userId);
            if (deleted == 1) {
                if (answerCounterService.updateLikeCount(answerId, -1) != 1) {
                    throw new ContentFailedException("取消点赞失败");
                }
                changed = true;
            }
        }

        if (changed && targetLiked && !answer.getUserId().equals(userId)) {
            NotificationEventMessage likeNotification = NotificationEventMessage.builder()
                    .recipientUserId(answer.getUserId())
                    .actorUserId(userId)
                    .type(NotificationType.LIKE_ANSWER.getCode())
                    .content("点赞了你的回答")
                    .payload(Map.of("contentId", answer.getQuestionId(), "answerId", answerId))
                    .build();
            outboxEventService.createNotificationEvent(likeNotification, ModerationTargetType.ANSWER.name(), answerId);
        }
        if (changed) {
            outboxEventService.createSearchReconcileEvent(
                    ModerationTargetType.ANSWER.name(), answerId, targetLiked ? "LIKE" : "UNLIKE");
        }

        final boolean stateChanged = changed;
        if (stateChanged && TransactionSynchronizationManager.isActualTransactionActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    try {
                        if (targetLiked) {
                            stringRedisTemplate.opsForZSet().add(key, userId.toString(), System.currentTimeMillis());
                        } else {
                            stringRedisTemplate.opsForZSet().remove(key, userId.toString());
                        }
                    } catch (Exception e) {
                        log.error("回答点赞缓存同步失败: answerId={}, userId={}", answerId, userId, e);
                    }
                }
            });
        }

        QuestionAnswer updatedAnswer = questionMapper.selectById(answerId);
        int likeCount = updatedAnswer.getLikeCount() != null ? updatedAnswer.getLikeCount() : 0;
        return LikeResultVO.builder().likedCount(likeCount).isLiked(targetLiked).build();
    }
}
