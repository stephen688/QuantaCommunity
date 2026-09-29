package com.quanta.demo0.answer.service.impl;

import com.quanta.demo0.answer.dto.AnswerDTO;
import com.quanta.demo0.answer.entity.QuestionAnswer;
import com.quanta.demo0.answer.service.AnswerAuditService;
import com.quanta.demo0.answer.service.AnswerCommandService;
import com.quanta.demo0.answer.vo.AnswerVO;
import com.quanta.demo0.content.entity.Content;
import com.quanta.demo0.content.exception.ContentFailedException;
import com.quanta.demo0.interaction.mapper.AnswerInteractionMapper;
import com.quanta.demo0.mapper.ContentMapper;
import com.quanta.demo0.mapper.QuestionMapper;
import com.quanta.demo0.mapper.UserMapper;
import com.quanta.demo0.moderation.enums.ModerationTargetType;
import com.quanta.demo0.moderation.properties.AliyunModerationProperties;
import com.quanta.demo0.moderation.utils.SensitiveWordChecker;
import com.quanta.demo0.notification.enums.NotificationType;
import com.quanta.demo0.notification.mq.message.NotificationEventMessage;
import com.quanta.demo0.platform.common.enums.AuditStatus;
import com.quanta.demo0.platform.mq.service.OutboxEventService;
import com.quanta.demo0.platform.security.context.BaseContext;
import com.quanta.demo0.rag.vector.AnswerVectorSyncService;
import com.quanta.demo0.user.vo.UserAuthInfoVO;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;
import java.util.Map;

import static com.quanta.demo0.platform.redis.constant.RedisConstants.ANSWER_LIKED_KEY;

/** 回答核心状态变更实现，负责发布、采纳和删除，不处理查询或互动关系。 */
@Service
@Slf4j
public class AnswerCommandServiceImpl implements AnswerCommandService {

    @Autowired
    private SensitiveWordChecker sensitiveWordChecker;
    @Autowired
    private ContentMapper contentMapper;
    @Autowired
    private UserMapper userMapper;
    @Autowired
    private QuestionMapper questionMapper;
    @Autowired
    private AnswerInteractionMapper answerInteractionMapper;
    @Autowired
    private StringRedisTemplate stringRedisTemplate;
    @Autowired
    private AnswerVectorSyncService answerVectorSyncService;
    @Autowired
    private OutboxEventService outboxEventService;
    @Autowired
    private AliyunModerationProperties moderationProperties;
    @Autowired
    private AnswerAuditService answerAuditService;

    @Override
    @Transactional
    public AnswerVO publishAnswer(AnswerDTO answerDTO) {
        if (answerDTO == null) {
            throw new ContentFailedException("参数不能为空");
        }
        if (answerDTO.getQuestionId() == null) {
            throw new ContentFailedException("问题 ID 不能为空");
        }
        if (StringUtils.isBlank(answerDTO.getContent())) {
            throw new ContentFailedException("回答内容不能为空");
        }
        String firstHit = sensitiveWordChecker.findFirstHit(answerDTO.getContent());
        if (firstHit != null) {
            throw new ContentFailedException("回答内容包含敏感词：" + firstHit);
        }

        Content content = contentMapper.selectById(answerDTO.getQuestionId());
        if (content == null) {
            throw new ContentFailedException("问题不存在");
        }
        if (content.getContentType() != 2) {
            throw new ContentFailedException("仅专业区问题可回答");
        }
        if (content.getAuditStatus() != 1) {
            throw new ContentFailedException("问题未通过审核");
        }

        Long userId = BaseContext.getCurrentId();
        QuestionAnswer answer = QuestionAnswer.builder()
                .questionId(answerDTO.getQuestionId())
                .userId(userId)
                .content(answerDTO.getContent())
                .likeCount(0)
                .commentCount(0)
                .isAccepted(0)
                .auditStatus(AuditStatus.PENDING.getCode())
                .isDeleted(0)
                .createTime(LocalDateTime.now())
                .updateTime(LocalDateTime.now())
                .build();
        questionMapper.insertAnswer(answer);
        if (answer.getAnswerId() == null) {
            throw new ContentFailedException("回答发布失败");
        }
        if (shouldModerateAnswer()) {
            outboxEventService.createAnswerModerationEvent(answer);
        } else if (isAutoApproveWhenModerationDisabled()) {
            answerAuditService.approveAnswer(answer.getAnswerId());
        }

        UserAuthInfoVO userInfo = userMapper.selectUserAuthInfoById(userId);
        return AnswerVO.builder()
                .answerId(answer.getAnswerId())
                .questionId(answer.getQuestionId())
                .userId(answer.getUserId())
                .nickName(userInfo != null ? userInfo.getNickName() : "未知用户")
                .avatarUrl(userInfo != null ? userInfo.getAvatarUrl() : "")
                .quantaBatch(userInfo != null ? userInfo.getQuantaBatch() : "")
                .content(answer.getContent())
                .likeCount(answer.getLikeCount())
                .commentCount(answer.getCommentCount())
                .isAccepted(answer.getIsAccepted())
                .auditStatus(answer.getAuditStatus())
                .createTime(answer.getCreateTime())
                .build();
    }

    @Override
    @Transactional
    public void acceptAnswer(Long answerId) {
        QuestionAnswer answer = questionMapper.selectById(answerId);
        if (answer == null) {
            throw new ContentFailedException("回答不存在");
        }
        if (answer.getAuditStatus() == null || answer.getAuditStatus() != 1) {
            throw new ContentFailedException("回答未通过审核");
        }
        Content question = contentMapper.selectByIdForUpdate(answer.getQuestionId());
        if (question == null) {
            throw new ContentFailedException("问题不存在");
        }
        Long currentUserId = BaseContext.getCurrentId();
        if (!question.getPublishUserId().equals(currentUserId)) {
            throw new ContentFailedException("只有问题发布者可采纳回答");
        }
        QuestionAnswer existingAccepted = questionMapper.selectAnswerByQuestionId(question.getContentId());
        if (existingAccepted != null && existingAccepted.getAnswerId().equals(answerId)) {
            return;
        }
        questionMapper.clearAcceptedAnswer(answer.getQuestionId());
        if (questionMapper.acceptAnswer(answerId) <= 0) {
            throw new ContentFailedException("采纳回答失败");
        }

        NotificationEventMessage acceptNotification = NotificationEventMessage.builder()
                .recipientUserId(answer.getUserId())
                .actorUserId(currentUserId)
                .type(NotificationType.ANSWER_ACCEPTED.getCode())
                .content("你的回答被采纳")
                .payload(Map.of("contentId", question.getContentId(), "answerId", answerId))
                .build();
        outboxEventService.createNotificationEvent(acceptNotification, ModerationTargetType.ANSWER.name(), answerId);
        if (existingAccepted != null) {
            outboxEventService.createSearchReconcileEvent(ModerationTargetType.ANSWER.name(), existingAccepted.getAnswerId(), "ACCEPT_CLEARED");
        }
        outboxEventService.createSearchReconcileEvent(ModerationTargetType.ANSWER.name(), answerId, "ACCEPTED");
    }

    @Override
    @Transactional
    public void deleteAnswer(Long answerId) {
        if (answerId == null) {
            throw new ContentFailedException("answerId 不能为空");
        }
        QuestionAnswer answer = questionMapper.selectById(answerId);
        if (answer == null) {
            throw new ContentFailedException("回答不存在");
        }
        Long currentUserId = BaseContext.getCurrentId();
        boolean isAnswerAuthor = answer.getUserId().equals(currentUserId);
        Content question = contentMapper.selectById(answer.getQuestionId());
        if (question == null) {
            throw new ContentFailedException("问题不存在");
        }
        if (!isAnswerAuthor && !question.getPublishUserId().equals(currentUserId)) {
            throw new ContentFailedException("您没有删除回答权限");
        }
        answerInteractionMapper.deleteAnswerLikedByAnswerId(answerId);
        answerInteractionMapper.deleteAnswerCommentImages(answerId);
        answerInteractionMapper.deleteAnswerCommentLiked(answerId);
        answerInteractionMapper.softDeleteAnswerComments(answerId);
        questionMapper.softDeleteAnswer(answerId);
        outboxEventService.createSearchReconcileEvent(ModerationTargetType.ANSWER.name(), answerId, "DELETE");

        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    try {
                        stringRedisTemplate.delete(ANSWER_LIKED_KEY + answerId);
                        answerVectorSyncService.deleteByAnswerId(answerId);
                    } catch (Exception e) {
                        log.error("回答删除后置同步失败: answerId={}", answerId, e);
                    }
                }
            });
        }
    }

    private boolean shouldModerateAnswer() {
        if (!moderationProperties.isEnabled()) {
            return false;
        }
        AliyunModerationProperties.TargetConfig answerConfig = getAnswerTargetConfig();
        return answerConfig != null && answerConfig.isEnabled();
    }

    private boolean isAutoApproveWhenModerationDisabled() {
        AliyunModerationProperties.TargetConfig answerConfig = getAnswerTargetConfig();
        String policy = answerConfig != null ? answerConfig.getDisabledPolicy() : "PENDING";
        return "APPROVED".equalsIgnoreCase(policy);
    }

    private AliyunModerationProperties.TargetConfig getAnswerTargetConfig() {
        AliyunModerationProperties.Targets targets = moderationProperties.getTargets();
        return targets != null ? targets.getAnswer() : null;
    }
}
