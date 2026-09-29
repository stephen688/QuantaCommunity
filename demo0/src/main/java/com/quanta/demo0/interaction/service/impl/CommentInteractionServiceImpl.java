package com.quanta.demo0.interaction.service.impl;

import com.quanta.demo0.comment.exception.CommentFailedException;
import com.quanta.demo0.comment.service.CommentCounterService;
import com.quanta.demo0.comment.vo.CommentSnapshotVO;
import com.quanta.demo0.interaction.dto.CommentReportDTO;
import com.quanta.demo0.interaction.entity.CommentReport;
import com.quanta.demo0.interaction.mapper.CommentInteractionMapper;
import com.quanta.demo0.interaction.service.CommentInteractionService;
import com.quanta.demo0.interaction.vo.LikeResultVO;
import com.quanta.demo0.moderation.enums.ModerationTargetType;
import com.quanta.demo0.notification.enums.NotificationType;
import com.quanta.demo0.notification.mq.message.NotificationEventMessage;
import com.quanta.demo0.notification.mq.producer.NotificationEventProducer;
import com.quanta.demo0.platform.redis.constant.RedisConstants;
import com.quanta.demo0.platform.security.context.BaseContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 评论互动服务实现。
 *
 * 点赞明细和计数更新在同一事务中完成，计数统一委托 CommentCounterService；
 * 通知 Outbox 仍与点赞写入共用事务，Redis 仅在提交后同步。
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class CommentInteractionServiceImpl implements CommentInteractionService {

    private final CommentInteractionMapper commentInteractionMapper;
    private final CommentCounterService commentCounterService;
    private final NotificationEventProducer notificationEventProducer;
    private final StringRedisTemplate stringRedisTemplate;

    /**
     * 写入或删除当前用户的点赞关系，并同步评论点赞计数与通知 Outbox。
     *
     * @param commentId 评论 ID
     * @param targetLiked 期望的点赞状态
     * @return 最新点赞状态和数量
     */
    @Override
    @Transactional
    public LikeResultVO likeComment(Long commentId, boolean targetLiked) {
        if (commentId == null) {
            throw new CommentFailedException("评论ID不能为空");
        }
        CommentSnapshotVO comment = commentCounterService.getCommentSnapshot(commentId);
        if (comment == null) {
            throw new CommentFailedException("评论不存在");
        }

        Long userId = BaseContext.getCurrentId();
        boolean changed = false;
        if (targetLiked) {
            int inserted = commentInteractionMapper.insertCommentLikes(commentId, userId);
            if (inserted == 1) {
                if (commentCounterService.changeCommentLikeCount(commentId, 1) != 1) {
                    throw new CommentFailedException("点赞失败");
                }
                changed = true;
            }
        } else if (commentInteractionMapper.deleteCommentLikeByUser(commentId, userId) == 1) {
            if (commentCounterService.changeCommentLikeCount(commentId, -1) != 1) {
                throw new CommentFailedException("取消点赞失败");
            }
            changed = true;
        }

        if (changed && targetLiked && !comment.getUserId().equals(userId)) {
            NotificationEventMessage notification = NotificationEventMessage.builder()
                    .recipientUserId(comment.getUserId())
                    .actorUserId(userId)
                    .type(NotificationType.LIKE_COMMENT.getCode())
                    .content("点赞了你的评论")
                    .payload(Map.of("contentId", comment.getContentId(), "commentId", commentId))
                    .build();
            notificationEventProducer.createNotificationEvent(
                    notification, ModerationTargetType.COMMENT.name(), commentId);
        }

        if (changed && TransactionSynchronizationManager.isActualTransactionActive()) {
            registerLikeCacheSync(commentId, userId, targetLiked);
        }

        CommentSnapshotVO latest = commentCounterService.getCommentSnapshot(commentId);
        int likeCount = latest == null || latest.getLikeCount() == null ? 0 : latest.getLikeCount();
        return LikeResultVO.builder().isLiked(targetLiked).likedCount(likeCount).build();
    }

    /**
     * 查询指定用户对评论集合的点赞关系。
     *
     * <p>点赞关系属于 interaction 域，评论查询只通过该公开端口读取。</p>
     */
    @Override
    public Set<Long> getLikedCommentIds(Long userId, List<Long> commentIds) {
        if (userId == null || commentIds == null || commentIds.isEmpty()) {
            return Collections.emptySet();
        }
        Set<Long> likedIds = commentInteractionMapper.selectCommentLikeIds(userId, commentIds);
        return likedIds == null ? Collections.emptySet() : likedIds;
    }

    /** 删除指定内容下评论的全部点赞关系。 */
    @Override
    @Transactional
    public void deleteByContentId(Long contentId) {
        if (contentId == null) {
            throw new CommentFailedException("contentId 不能为空");
        }
        commentInteractionMapper.deleteCommentLikesByContentId(contentId);
    }

    /** 删除单条评论的全部点赞关系。 */
    @Override
    @Transactional
    public void deleteByCommentId(Long commentId) {
        if (commentId == null) {
            throw new CommentFailedException("commentId 不能为空");
        }
        commentInteractionMapper.deleteCommentLikes(commentId);
    }

    /** 删除指定回复集合的全部点赞关系。 */
    @Override
    @Transactional
    public void deleteByCommentIds(List<Long> commentIds) {
        if (commentIds == null || commentIds.isEmpty()) {
            return;
        }
        commentInteractionMapper.deleteCommentLikesByCommentIds(commentIds);
    }

    /**
     * 校验并写入评论举报关系，重复有效举报直接拒绝。
     *
     * @param commentReportDTO 举报请求
     */
    @Override
    @Transactional
    public void reportComment(CommentReportDTO commentReportDTO) {
        if (commentReportDTO == null || commentReportDTO.getCommentId() == null) {
            throw new CommentFailedException("评论ID不能为空");
        }
        if (commentReportDTO.getReportType() == null
                || commentReportDTO.getReportType() < 1
                || commentReportDTO.getReportType() > 5) {
            throw new CommentFailedException("举报类型不合法（1-垃圾广告 2-人身攻击 3-违规内容 4-虚假信息 5-其他）");
        }
        if (commentCounterService.getCommentSnapshot(commentReportDTO.getCommentId()) == null) {
            throw new CommentFailedException("评论不存在");
        }

        Long reporterId = BaseContext.getCurrentId();
        if (commentInteractionMapper.selectValidReportByCommentAndUser(
                commentReportDTO.getCommentId(), reporterId) != null) {
            throw new CommentFailedException("您已举报过该评论，请勿重复举报");
        }

        LocalDateTime now = LocalDateTime.now();
        commentInteractionMapper.insertCommentReport(CommentReport.builder()
                .commentId(commentReportDTO.getCommentId())
                .reportType(commentReportDTO.getReportType())
                .reporterId(reporterId)
                .status(0)
                .isDeleted(0)
                .createTime(now)
                .updateTime(now)
                .build());
    }

    private void registerLikeCacheSync(Long commentId, Long userId, boolean liked) {
        String key = RedisConstants.COMMENT_LIKED_KEY + commentId;
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    if (liked) {
                        stringRedisTemplate.opsForZSet().add(
                                key, userId.toString(), System.currentTimeMillis());
                    } else {
                        stringRedisTemplate.opsForZSet().remove(key, userId.toString());
                    }
                } catch (Exception e) {
                    log.error("评论点赞缓存同步失败，businessType=COMMENT_LIKE, userId={}, targetId={}, targetState={}",
                            userId, commentId, liked, e);
                }
            }
        });
    }
}
