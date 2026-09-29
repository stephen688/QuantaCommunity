package com.quanta.demo0.answer.service.impl;

import com.quanta.demo0.answer.dto.AnswerDTO;
import com.quanta.demo0.answer.entity.QuestionAnswer;
import com.quanta.demo0.answer.service.AnswerAuditService;
import com.quanta.demo0.answer.service.AnswerCommandService;
import com.quanta.demo0.answer.vo.AnswerVO;
import com.quanta.demo0.content.service.ContentQueryService;
import com.quanta.demo0.content.vo.ContentSnapshotVO;
import com.quanta.demo0.content.exception.ContentFailedException;
import com.quanta.demo0.answer.mapper.QuestionMapper;
import com.quanta.demo0.interaction.service.AnswerInteractionService;
import com.quanta.demo0.moderation.enums.ModerationTargetType;
import com.quanta.demo0.moderation.properties.AliyunModerationProperties;
import com.quanta.demo0.moderation.utils.SensitiveWordChecker;
import com.quanta.demo0.notification.enums.NotificationType;
import com.quanta.demo0.notification.mq.message.NotificationEventMessage;
import com.quanta.demo0.notification.mq.producer.NotificationEventProducer;
import com.quanta.demo0.platform.common.enums.AuditStatus;
import com.quanta.demo0.platform.security.context.BaseContext;
import com.quanta.demo0.rag.vector.AnswerVectorSyncService;
import com.quanta.demo0.answer.mq.producer.AnswerEventProducer;
import com.quanta.demo0.search.mq.producer.SearchEventProducer;
import com.quanta.demo0.user.vo.UserAuthInfoVO;
import com.quanta.demo0.user.service.UserQueryService;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static com.quanta.demo0.platform.redis.constant.RedisConstants.ANSWER_LIKED_KEY;

/** 回答核心状态变更实现，负责发布、采纳和删除，不处理查询或互动关系。 */
@Service
@Slf4j
public class AnswerCommandServiceImpl implements AnswerCommandService {

    @Autowired
    private SensitiveWordChecker sensitiveWordChecker;
    @Autowired
    private ContentQueryService contentQueryService;
    @Autowired
    private UserQueryService userQueryService;
    @Autowired
    private QuestionMapper questionMapper;
    @Autowired
    private AnswerInteractionService answerInteractionService;
    @Autowired
    private StringRedisTemplate stringRedisTemplate;
    @Autowired
    private AnswerVectorSyncService answerVectorSyncService;
    @Autowired
    private AnswerEventProducer answerEventProducer;
    @Autowired
    private NotificationEventProducer notificationEventProducer;
    @Autowired
    private SearchEventProducer searchEventProducer;
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

        ContentSnapshotVO content = contentQueryService.getContentSnapshot(answerDTO.getQuestionId());
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
            answerEventProducer.createAnswerModerationEvent(answer);
        } else if (isAutoApproveWhenModerationDisabled()) {
            answerAuditService.approveAnswer(answer.getAnswerId());
        }

        UserAuthInfoVO userInfo = userQueryService.getUserAuthInfo(userId);
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
        ContentSnapshotVO question = contentQueryService.lockContentSnapshot(answer.getQuestionId());
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
        notificationEventProducer.createNotificationEvent(acceptNotification, ModerationTargetType.ANSWER.name(), answerId);
        if (existingAccepted != null) {
            searchEventProducer.createSearchReconcileEvent(ModerationTargetType.ANSWER.name(), existingAccepted.getAnswerId(), "ACCEPT_CLEARED");
        }
        searchEventProducer.createSearchReconcileEvent(ModerationTargetType.ANSWER.name(), answerId, "ACCEPTED");
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
        ContentSnapshotVO question = contentQueryService.getContentSnapshot(answer.getQuestionId());
        if (question == null) {
            throw new ContentFailedException("问题不存在");
        }
        if (!isAnswerAuthor && !question.getPublishUserId().equals(currentUserId)) {
            throw new ContentFailedException("您没有删除回答权限");
        }
        answerInteractionService.deleteByAnswerId(answerId);
        questionMapper.softDeleteAnswer(answerId);
        searchEventProducer.createSearchReconcileEvent(ModerationTargetType.ANSWER.name(), answerId, "DELETE");

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

    /**
     * 级联删除问题下全部未删除回答及其互动关联数据。
     *
     * <p>该方法保持在调用方事务内执行，返回回答 ID 供内容域登记索引删除事件。</p>
     */
    @Override
    @Transactional
    public List<Long> deleteByQuestionId(Long questionId) {
        if (questionId == null) {
            throw new ContentFailedException("questionId 不能为空");
        }
        List<QuestionAnswer> answers = questionMapper.selectAnswersByQuestionId(questionId);
        List<Long> answerIds = answers == null ? List.of() : answers.stream()
                .map(QuestionAnswer::getAnswerId)
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toList());
        questionMapper.softDeleteAnswers(questionId);
        return answerIds;
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
