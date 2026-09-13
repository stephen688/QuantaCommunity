package com.quanta.demo0.service.Impl;

import com.quanta.demo0.annotation.ModerationTargetType;
import com.quanta.demo0.constant.RedisConstants;
import com.quanta.demo0.dto.AnswerDTO;
import com.quanta.demo0.entity.*;
import com.quanta.demo0.enums.AuditStatus;
import com.quanta.demo0.enums.NotificationType;
import com.quanta.demo0.exception.ContentFailedException;
import com.quanta.demo0.mapper.ContentMapper;
import com.quanta.demo0.mapper.QuestionMapper;
import com.quanta.demo0.mapper.UserMapper;
import com.quanta.demo0.mq.message.NotificationEventMessage;
import com.quanta.demo0.properties.AliyunModerationProperties;
import com.quanta.demo0.rag.vector.AnswerVectorSyncService;
import com.quanta.demo0.service.AnswerAuditService;
import com.quanta.demo0.service.AnswerService;
import com.quanta.demo0.service.OutboxEventService;
import com.quanta.demo0.utils.SensitiveWordChecker;
import com.quanta.demo0.vo.AnswerVO;
import com.quanta.demo0.vo.LikeResultVO;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static com.quanta.demo0.constant.RedisConstants.ANSWER_LIKED_KEY;

/**
 * 回答主服务实现类。
 * 核心职责：
 * 1. 负责回答发布、查询、点赞、删除等主业务流程；
 * 2. 协调回答审核任务投递、审核结果处理与可见性控制；
 * 3. 在回答生命周期内维护通知、搜索索引、向量索引等衍生数据。
 * 设计说明：
 * - 关键写操作采用事务并结合 afterCommit 触发异步副作用；
 * - 通过审核状态门控回答可见范围，保证前台展示与治理规则一致。
 */
@Service
@Slf4j
public class AnswerServiceImpl implements AnswerService {

    @Autowired
    private SensitiveWordChecker sensitiveWordChecker;
    @Autowired
    private ContentMapper contentMapper;
    @Autowired
    private UserMapper userMapper;
    @Autowired
    private QuestionMapper questionMapper;
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

    /**
     * 发布回答
     * 【核心步骤】
     * 1. 敏感词校验 → 2. 参数校验 → 3. 问题校验
     * 4. 构建回答实体 → 5. 插入回答 → 6. 查询用户信息
     * 7. 事务提交后发送 AI 审核任务或按配置自动通过
     * 8. 构建返回 VO
     * 【规则说明】
     * - 仅专业区问题可回答（contentType=2）
     * - 问题必须存在且审核通过
     * - 敏感词校验
     * - 入库为待审（auditStatus=0），审核通过后再同步 ES/向量
     * @param answerDTO 回答数据（questionId、content）
     * @return 回答 VO
     */



    @Transactional
    @Override
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
// 最后再做敏感词校验
        String firstHit = sensitiveWordChecker.findFirstHit(answerDTO.getContent());
        if (firstHit != null) {
            throw new ContentFailedException("回答内容包含敏感词：" + firstHit);
        }
        // 3. 问题校验
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
        // 4. 构建回答实体
        Long userId= BaseContext.getCurrentId();
        QuestionAnswer answer= QuestionAnswer.builder()
                .questionId(answerDTO.getQuestionId())
                .userId(userId)
                .content(answerDTO.getContent())
                .likeCount(0)
                .commentCount(0)
                .isAccepted(0)
                // AI 云审核：入库为待审，审核通过后再同步 ES/向量
                .auditStatus(AuditStatus.PENDING.getCode())
                // .auditStatus(1)  // 原：默认审核通过
                .isDeleted(0)
                .createTime(LocalDateTime.now())
                .updateTime(LocalDateTime.now())
                .build();

        // 5. 插入回答
        questionMapper.insertAnswer(answer);

// 数据库生成 answerId 后才能创建审核事件。
        if (answer.getAnswerId() == null) {
            throw new ContentFailedException("回答发布失败");
        }

// 6. 审核开启时，在当前事务中写入 Outbox，不再直接发送 RabbitMQ。
        if (shouldModerateAnswer()) {
            outboxEventService.createAnswerModerationEvent(answer);
        } else if (isAutoApproveWhenModerationDisabled()) {
            // 审核关闭且策略为 APPROVED 时，直接加入当前事务完成自动通过。
            answerAuditService.approveAnswer(answer.getAnswerId());
        }

        // 7. 查询用户信息
        UserAuthInfo userInfo = userMapper.selectUserAuthInfoById(userId);
        // 8. 构建返回 VO（auditStatus=0 表示审核中）
         AnswerVO answerVO = AnswerVO.builder()
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

         // 9. 返回结果
        return answerVO;
    }



    /** 全局开关 + 回答类型开关均开启时才走 AI 审核 */
    private boolean shouldModerateAnswer() {
        if (!moderationProperties.isEnabled()) {
            return false;
        }
        AliyunModerationProperties.TargetConfig answerConfig = getAnswerTargetConfig();
        return answerConfig != null && answerConfig.isEnabled();
    }

    /** AI 审核关闭时，disabledPolicy=APPROVED 则敏感词通过后自动通过 */
    private boolean isAutoApproveWhenModerationDisabled() {
        AliyunModerationProperties.TargetConfig answerConfig = getAnswerTargetConfig();
        String policy = answerConfig != null ? answerConfig.getDisabledPolicy() : "PENDING";
        return "APPROVED".equalsIgnoreCase(policy);
    }

    private AliyunModerationProperties.TargetConfig getAnswerTargetConfig() {
        AliyunModerationProperties.Targets targets = moderationProperties.getTargets();
        return targets != null ? targets.getAnswer() : null;
    }
    /**
     * 查询问题的回答列表
     * 【核心步骤】
     * 1. 校验问题是否存在 → 2. 查询回答列表
     * 3. 批量查询用户信息 → 4. 构建返回 VO 列表
     * 【排序规则】
     * - 已采纳的回答排在最前面
     * - 点赞数多的排在前面
     * - 时间新的排在前面
     * @param questionId 问题 ID
     * @return 回答列表
     */

    @Override
    public List<AnswerVO> getAnswersByQuestionId(Long questionId) {
       //1.校验问题是否存在
        Content content = contentMapper.selectById(questionId);
        if (content == null) {
            throw new ContentFailedException("问题不存在");
        }
        //2.查询回答列表
        List<QuestionAnswer> answers = questionMapper.selectAnswersByQuestionId(questionId);
        if (answers == null || answers.isEmpty()) {
            return  new ArrayList<>(); // 返回空列表
        }
        // 3. 批量查询用户信息
        // 3.1 提取所有回答的用户 ID
        Set<Long> userIds = answers.stream()
                .map(QuestionAnswer::getUserId)
                .collect(Collectors.toSet());

        // 3.2 批量查询用户信息
        List<UserAuthInfo> userInfoList = userMapper.selectUserAuthInfoByIds(new ArrayList<>(userIds));

        // 3.3 构建用户信息映射（userId -> UserAuthInfo）
        Map<Long, UserAuthInfo> userInfoMap = userInfoList.stream()
                .collect(Collectors.toMap(UserAuthInfo::getUserId, u -> u));

        // 4. 构建返回 VO 列表
        List<AnswerVO> answerVOList = new ArrayList<>();
        for (QuestionAnswer answer : answers) {
            UserAuthInfo userInfo = userInfoMap.get(answer.getUserId());
            AnswerVO vo = AnswerVO.builder()
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
                    .createTime(answer.getCreateTime())
                    .build();
            answerVOList.add(vo);
        }
        // 5. 返回结果
        return answerVOList;

    }
    /**
     * 采纳回答（仅题主可操作）
     * 【核心步骤】
     * 1. 校验回答是否存在 → 2. 校验当前用户是否为题主
     * 3. 事务内：先取消旧采纳，再设置新采纳
     * 【规则说明】
     * - 仅问题发布者（题主）可采纳
     * - 一个问题只能有一条回答被采纳
     * - 采纳新回答时会自动取消旧的采纳
     *
     * @param answerId 回答 ID
     */
    @Transactional
    @Override
    public void acceptAnswer(Long answerId) {

        // 1. 校验回答是否存在
        QuestionAnswer answer = questionMapper.selectById(answerId);
        if (answer == null) {
            throw new ContentFailedException("回答不存在");
        }
        if (answer.getAuditStatus() == null || answer.getAuditStatus() != 1) {
            throw new ContentFailedException("回答未通过审核");
        }

        // 2. 校验当前用户是否为题主
        Content question = contentMapper.selectByIdForUpdate(answer.getQuestionId());
        if (question == null) {
            throw new ContentFailedException("问题不存在");
        }


        Long currentUserId = BaseContext.getCurrentId();
        if (!question.getPublishUserId().equals(currentUserId)) {
            throw new ContentFailedException("只有问题发布者可采纳回答");
        }

        //检验是否已经采纳了这个回答，如果已经采纳了这个回答，就直接返回，不需要重复操作
        QuestionAnswer existingAccepted = questionMapper.selectAnswerByQuestionId(question.getContentId());
        if (existingAccepted != null && existingAccepted.getAnswerId().equals(answerId)) {
            return; // 已经采纳，直接返回
        }
        // 3. 事务内：先取消旧采纳，再设置新采纳
        // 3.1 取消旧采纳（如果有）
        questionMapper.clearAcceptedAnswer(answer.getQuestionId());

        // 3.2 设置新采纳
       int rows=questionMapper.acceptAnswer(answerId);
         if (rows <= 0) {
              throw new ContentFailedException("采纳回答失败");
         }
        // 采纳状态真正发生变化后，在当前事务中登记通知 Outbox。
        NotificationEventMessage acceptNotification = NotificationEventMessage.builder()
                .recipientUserId(answer.getUserId())
                .actorUserId(currentUserId)
                .type(NotificationType.ANSWER_ACCEPTED.getCode())
                .content("你的回答被采纳")
                .payload(Map.of(
                        "contentId", question.getContentId(),
                        "answerId", answerId
                ))
                .build();

        outboxEventService.createNotificationEvent(acceptNotification, ModerationTargetType.ANSWER.name(), answerId);

        // 旧采纳和新采纳都发生了变化，分别登记搜索重建事件。
        if (existingAccepted != null) {
            outboxEventService.createSearchReconcileEvent(ModerationTargetType.ANSWER.name(), existingAccepted.getAnswerId(), "ACCEPT_CLEARED");
        }
        outboxEventService.createSearchReconcileEvent(ModerationTargetType.ANSWER.name(), answerId, "ACCEPTED");
    }
    /**
     * 设置回答点赞状态。
     * MySQL 点赞明细表是状态和计数变更的最终依据，Redis 仅用于提交后的状态同步。
     */
    @Transactional
    @Override
    public LikeResultVO likeAnswer(Long answerId, boolean targetLiked) {
        // 1. 参数校验
        if (answerId == null) {
            throw new ContentFailedException("answerId 不能为空");
        }

        // 2. 查询回答是否存在
        QuestionAnswer answer = questionMapper.selectById(answerId);
        if (answer == null) {
            throw new ContentFailedException("回答不存在");
        }

        // 3. 获取当前用户 ID
        Long userId = BaseContext.getCurrentId();

        // 4. 根据请求目标状态操作 MySQL 点赞明细
        String key = ANSWER_LIKED_KEY + answerId;
        boolean changed = false;
        boolean isLiked = targetLiked;

        if (targetLiked) {
            AnswerLiked answerLiked = AnswerLiked.builder()
                    .answerId(answerId)
                    .userId(userId)
                    .createTime(LocalDateTime.now())
                    .build();
            int inserted = questionMapper.insertAnswerLiked(answerLiked);

            // 只有真正新增点赞明细，才增加回答点赞数
            if (inserted == 1) {
                int rows = questionMapper.updateAnswerLikeCount(answerId, 1);
                if (rows != 1) {
                    throw new ContentFailedException("点赞失败");
                }
                changed = true;
            }
        } else {
            int deleted = questionMapper.deleteAnswerLikedByUser(answerId, userId);

            // 只有真正删除点赞明细，才减少回答点赞数
            if (deleted == 1) {
                int rows = questionMapper.updateAnswerLikeCount(answerId, -1);
                if (rows != 1) {
                    throw new ContentFailedException("取消点赞失败");
                }
                changed = true;
            }
        }
        // 只有真正新增点赞时才产生通知，重复请求不会创建重复 Outbox。
        if (changed && isLiked && !answer.getUserId().equals(userId)) {
            NotificationEventMessage likeNotification = NotificationEventMessage.builder()
                    .recipientUserId(answer.getUserId())
                    .actorUserId(userId)
                    .type(NotificationType.LIKE_ANSWER.getCode())
                    .content("点赞了你的回答")
                    .payload(Map.of(
                            "contentId", answer.getQuestionId(),
                            "answerId", answerId
                    ))
                    .build();

            // 回答点赞明细、点赞数和通知 Outbox 一起提交。
            outboxEventService.createNotificationEvent(likeNotification, ModerationTargetType.ANSWER.name(), answerId);
        }
        // ES 中保存了回答点赞数，状态真正变化时才登记重建事件。
        if (changed) {
            outboxEventService.createSearchReconcileEvent(ModerationTargetType.ANSWER.name(), answerId, isLiked ? "LIKE" : "UNLIKE");
        }
        // 5. 事务提交后同步 Redis 和发送副作用消息
        final boolean stateChanged = changed;
        if (stateChanged && TransactionSynchronizationManager.isActualTransactionActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    try {
                        if (isLiked) {
                            stringRedisTemplate.opsForZSet().add(key, userId.toString(), System.currentTimeMillis());
                        } else {
                            stringRedisTemplate.opsForZSet().remove(key, userId.toString());
                        }
                    } catch (Exception e) {
                        log.error("回答点赞缓存同步失败，businessType=ANSWER_LIKE, userId={}, targetId={}, targetState={}",
                                userId, answerId, isLiked, e);
                    }
                }
            });
        }

        // 6. 查询最新的点赞数（确保数据一致性）
        QuestionAnswer updatedAnswer = questionMapper.selectById(answerId);
        int likeCount = updatedAnswer.getLikeCount() != null ? updatedAnswer.getLikeCount() : 0;

        // 7. 返回结果
        return LikeResultVO.builder()
                .likedCount(likeCount)
                .isLiked(isLiked)
                .build();
    }

    /**
     * 删除回答（仅回答作者或题主可操作）
     * 【核心步骤】（严格复用删除内容逻辑）
     * 1. 参数校验 → 2. 查询回答是否存在
     * 3. 权限校验（回答作者或题主）
     * 4. 物理删除回答点赞记录
     * 5. 物理删除评论下的图片
     * 6. 物理删除评论下的点赞记录
     * 7. 软删除评论
     * 8. 软删除回答本身
     * 9. 事务提交后：删除 Redis 记录
     * @param answerId 回答 ID
     */
    @Transactional
    @Override
    public void deleteAnswer(Long answerId) {
        // 1. 参数校验
        if (answerId == null) {
            throw new ContentFailedException("answerId 不能为空");
        }

        // 2. 查询回答是否存在
        QuestionAnswer answer = questionMapper.selectById(answerId);
        if (answer == null) {
            throw new ContentFailedException("回答不存在");
        }

        // 3. 权限校验（回答作者或题主）
        Long currentUserId = BaseContext.getCurrentId();
        boolean isAnswerAuthor = answer.getUserId().equals(currentUserId);

        // 查询问题信息，判断是否为题主
        Content question = contentMapper.selectById(answer.getQuestionId());
        if (question == null) {
            throw new ContentFailedException("问题不存在");
        }
        boolean isQuestionAuthor = question.getPublishUserId().equals(currentUserId);

        if (!isAnswerAuthor && !isQuestionAuthor) {
            throw new ContentFailedException("您没有删除回答权限");
        }

        // 4. 物理删除回答点赞记录
        questionMapper.deleteAnswerLikedByAnswerId(answerId);

        // 5. 物理删除评论下的图片
        questionMapper.deleteAnswerCommentImages(answerId);

        // 6. 物理删除评论下的点赞记录
        questionMapper.deleteAnswerCommentLiked(answerId);

        // 7. 软删除评论
        questionMapper.softDeleteAnswerComments(answerId);

        // 8. 软删除回答本身
        questionMapper.softDeleteAnswer(answerId);

        // 删除结果与 Search Outbox 一起提交，ES 暂时不可用也能稍后重试删除索引。
        outboxEventService.createSearchReconcileEvent(ModerationTargetType.ANSWER.name(), answerId, "DELETE");

        // 9. 事务提交后：删除 Redis 记录
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    try {
                        // 删除 Redis 中的点赞记录
                        String likeKey = ANSWER_LIKED_KEY + answerId;
                        stringRedisTemplate.delete(likeKey);
                        // 向量库仍沿用原有提交后同步；ES 已改由 Search Outbox 处理。
                        answerVectorSyncService.deleteByAnswerId(answerId);
                        log.info("[RAG] 回答删除后同步向量成功: answerId={}", answerId);
                    } catch (Exception e) {
                        log.error("[RAG] 回答删除后同步向量失败: answerId={}", answerId, e);

                    }
                }
            });
        }
    }

    /**
     * 查询回答详情
     * 【核心步骤】
     * 1. 参数校验 → 2. 查询回答是否存在
     * 3. 查询回答用户信息 → 4. 构建返回 VO
     * @param answerId 回答 ID
     * @return 回答详情 VO
     */
    @Override
    public AnswerVO getAnswerDetail(Long answerId) {
        // 1. 参数校验
        if (answerId == null) {
            throw new ContentFailedException("answerId 不能为空");
        }

        // 2. 查询回答是否存在
        QuestionAnswer answer = questionMapper.selectById(answerId);
        if (answer == null) {
            throw new ContentFailedException("回答不存在");
        }
        //查询回答案是否被删除
        if (answer.getIsDeleted() != null && answer.getIsDeleted() == 1) {
            throw new ContentFailedException("回答已被删除");
        }

        // 3. 查询回答用户信息
        UserAuthInfo userInfo = userMapper.selectUserAuthInfoById(answer.getUserId());

        // 4. 构建返回 VO
        AnswerVO answerVO = AnswerVO.builder()
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
                .createTime(answer.getCreateTime())
                .build();

        return answerVO;
    }
}
