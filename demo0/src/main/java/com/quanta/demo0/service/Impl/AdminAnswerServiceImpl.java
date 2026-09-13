package com.quanta.demo0.service.Impl;

import com.github.pagehelper.Page;
import com.github.pagehelper.PageHelper;
import com.quanta.demo0.annotation.ModerationTargetType;
import com.quanta.demo0.constant.AdminAuditActionConstants;
import com.quanta.demo0.constant.RedisConstants;
import com.quanta.demo0.dto.AnswerAdminQueryDTO;
import com.quanta.demo0.dto.ContentAuditDTO;
import com.quanta.demo0.entity.QuestionAnswer;
import com.quanta.demo0.enums.NotificationType;
import com.quanta.demo0.exception.ContentFailedException;
import com.quanta.demo0.mapper.QuestionMapper;
import com.quanta.demo0.mq.message.NotificationEventMessage;
import com.quanta.demo0.rag.vector.AnswerVectorSyncService;
import com.quanta.demo0.result.PageResult;
import com.quanta.demo0.service.AdminAnswerService;
import com.quanta.demo0.service.AdminAuditRecorder;
import com.quanta.demo0.service.OutboxEventService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 管理端回答服务实现类。
 *
 * 核心职责：
 * 1. 提供回答分页查询、人工审核、删除等后台管理能力；
 * 2. 审核通过后同步搜索与向量索引，驳回后执行索引下线；
 * 3. 统一发送审核结果通知，保证作者侧反馈及时。
 *
 * 设计说明：
 * - 审核与删除流程以事务保障数据一致性；
 * - 与前台回答审核链路保持规则一致，避免双轨行为分叉。
 */
@Service
@Slf4j
public class AdminAnswerServiceImpl  implements AdminAnswerService {
    @Autowired
    private QuestionMapper questionMapper;

    @Autowired
    private AnswerVectorSyncService answerVectorSyncService;
    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private OutboxEventService outboxEventService;

    @Autowired
    private AdminAuditRecorder adminAuditRecorder;
    /**
     * 分页查询回答列表
     * <p>
     * 执行流程：
     * 1. PageHelper.startPage() 开启分页
     * 2. 调用 Mapper 执行 SQL 查询
     * 3. 封装为 PageResult 返回
     *
     * @param query 查询条件
     * @return 分页结果
     */
    @Override
    public PageResult pageQuery(AnswerAdminQueryDTO query) {
        PageHelper.startPage(query.getPageNum(), query.getPageSize());
        Page<QuestionAnswer> page = questionMapper.pageAdmin(query);
        return new PageResult(page.getTotal(), page.getResult());
    }

    /**
     * 管理端删除回答
     * 执行流程：
     * 1. 参数校验
     * 2. 查询回答是否存在
     * 3. 软删除回答下的评论
     * 4. 物理删除回答评论下的图片
     * 5. 物理删除回答评论下的点赞记录
     * 6. 物理删除回答点赞记录
     * 7. 软删除回答本身
     * 8. 更新帖子评论数（需要查询回答关联的帖子ID）
     *
     * @param answerId 回答 ID
     */
    @Override
    @Transactional
    public void deleteAnswer(Long answerId) {
        // 1. 参数校验
        if (answerId == null) {
            throw new ContentFailedException("回答 ID 不能为空");
        }

        // 2. 查询回答是否存在
        QuestionAnswer answer = questionMapper.selectById(answerId);
        if (answer == null) {
            throw new ContentFailedException("回答不存在");
        }

        // 3. 软删除回答下的评论
        questionMapper.softDeleteAnswerComments(answerId);

        // 4. 物理删除回答评论下的图片
        questionMapper.deleteAnswerCommentImages(answerId);

        // 5. 物理删除回答评论下的点赞记录
        questionMapper.deleteAnswerCommentLiked(answerId);

        // 6. 物理删除回答点赞记录
        questionMapper.deleteAnswerLikedByAnswerId(answerId);

        // 7. 软删除回答本身
        questionMapper.softDeleteAnswer(answerId);

        // 回答删除和搜索删除事件使用同一个 MySQL 事务。
        outboxEventService.createSearchReconcileEvent(ModerationTargetType.ANSWER.name(), answerId, "DELETE");

        // 8. 事务提交后：删除 Redis 点赞并同步向量库。
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    try {
                        // 删除 Redis 点赞记录
                        String likeKey = RedisConstants.ANSWER_LIKED_KEY + answerId;
                        stringRedisTemplate.delete(likeKey);

                        // ES 已由 Search Outbox 处理，这里保留原有向量库同步。
                        answerVectorSyncService.deleteByAnswerId(answerId);

                        log.info("管理端删除回答成功，answerId={}, contentId={}", answerId, answer.getQuestionId());
                    } catch (Exception e) {
                        log.error("管理端删除回答后置操作失败: answerId={}", answerId, e);
                    }
                }
            });
        } else {
            log.info("管理端删除回答成功，answerId={}, contentId={}", answerId, answer.getQuestionId());
        }

        // 审计：回答删除成功
        adminAuditRecorder.recordSuccess(
                AdminAuditActionConstants.CONTENT_DELETE,
                "ANSWER",
                String.valueOf(answerId),
                "deleted=0",
                "deleted=1"
        );

        //TODO 8. 更新帖子评论数（回答下的评论数减少）
        // 注意：这里需要根据实际业务逻辑决定是否需要更新帖子的 comment_count
        // 如果回答下的评论也计入帖子评论数，则需要更新

    }

    /**
     * 审核回答
     * 执行流程：
     * 1. 校验参数
     * 2. 查询回答是否存在
     * 3. 记录原审核状态
     * 4. 更新审核状态
     * 5. 根据状态变化同步 ES 和向量库
     * - 已通过 → 驳回：从 ES 和向量库删除
     * - 已驳回 → 通过：同步到 ES 和向量库
     *
     * @param auditDTO 审核信息
     */
    @Override
    @Transactional
    public void auditAnswer(ContentAuditDTO auditDTO) {
        Long answerId = auditDTO.resolveAnswerId();
        // 1. 校验参数
        if (answerId == null) {
            throw new ContentFailedException("回答 ID 不能为空");
        }
        if (auditDTO.getAuditResult() == null ||
                (auditDTO.getAuditResult() != 1 && auditDTO.getAuditResult() != 2)) {
            throw new ContentFailedException("审核结果不合法（1-通过 2-驳回）");
        }

        // 2. 查询回答是否存在
        QuestionAnswer answer = questionMapper.selectById(answerId);
        if (answer == null) {
            throw new ContentFailedException("回答不存在");
        }

        // 3. 记录原审核状态
        Integer oldAuditStatus = answer.getAuditStatus();

        // 状态没有变化时不重复更新，也不重复创建通知。
        if (oldAuditStatus.equals(auditDTO.getAuditResult())) {
            log.info("回答审核状态未变化，跳过更新 answerId={}, auditStatus={}", answerId, oldAuditStatus);
            return;
        }

        // SQL 同时匹配旧状态；如果 AI 已经先更新，管理员本次更新会影响 0 行。
        String rejectReason = auditDTO.getAuditResult() == 2 ? auditDTO.getRejectReason() : null;
        int updatedRows = questionMapper.updateAnswerAuditStatusIfCurrent(answerId, oldAuditStatus, auditDTO.getAuditResult(), rejectReason);

        if (updatedRows != 1) {
            throw new ContentFailedException("回答状态已被其他审核操作修改，请刷新后重试");
        }

        // 审核结果和 Search Outbox 一起提交；消费者会根据 MySQL 最新状态决定写入或删除 ES。
        outboxEventService.createSearchReconcileEvent(ModerationTargetType.ANSWER.name(), answerId, auditDTO.getAuditResult() == 1 ? "AUDIT_APPROVED" : "AUDIT_REJECTED");

        // 6. 向量库仍沿用原有提交后同步，ES 已由 Search Outbox 负责。
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    try {
                        if (oldAuditStatus == 0 && auditDTO.getAuditResult() == 1) {
                            // 待审 → 通过：写入向量库
                            answerVectorSyncService.upsertByAnswerId(answerId);
                            log.info("审核通过（待审→通过），已同步向量，answerId={}", answerId);
                        } else if (oldAuditStatus == 1 && auditDTO.getAuditResult() == 2) {
                            // 通过 → 驳回：从向量库删除
                            answerVectorSyncService.deleteByAnswerId(answerId);
                            log.info("审核驳回（通过→驳回），已从向量库删除，answerId={}", answerId);
                        } else if (oldAuditStatus == 2 && auditDTO.getAuditResult() == 1) {
                            // 驳回 → 通过：重新写入向量库
                            answerVectorSyncService.upsertByAnswerId(answerId);
                            log.info("审核通过（驳回→通过），已同步向量，answerId={}", answerId);
                        } else {
                            log.info("审核状态未发生索引相关变化，answerId={}, oldStatus={}, newStatus={}",
                                    answerId, oldAuditStatus, auditDTO.getAuditResult());
                        }
                    } catch (Exception e) {
                        log.error("管理端审核回答后向量同步失败: answerId={}", answerId, e);
                    }
                }
            });
        }

        log.info("管理端审核回答成功，answerId={}, auditResult={}", answerId, auditDTO.getAuditResult());

        // 管理审核状态与通知 Outbox 在同一个 MySQL 事务中提交。
        String notifyContent = auditDTO.getAuditResult() == 1 ? "你的回答已审核通过" : "你的回答审核未通过";
        if (auditDTO.getAuditResult() == 2 && auditDTO.getRejectReason() != null) {
            notifyContent += "，原因：" + auditDTO.getRejectReason();
        }

        NotificationEventMessage auditNotification = NotificationEventMessage.builder()
                .recipientUserId(answer.getUserId())
                .actorUserId(null)
                .type(NotificationType.ANSWER_AUDIT_RESULT.getCode())
                .content(notifyContent)
                .payload(Map.of("answerId", answerId, "auditResult", auditDTO.getAuditResult(), "rejectReason", rejectReason != null ? rejectReason : ""))
                .build();

        outboxEventService.createNotificationEvent(auditNotification, ModerationTargetType.ANSWER.name(), answerId);

        // 审计：回答审核成功
        adminAuditRecorder.recordSuccess(
                AdminAuditActionConstants.CONTENT_AUDIT,
                "ANSWER",
                String.valueOf(answerId),
                "auditStatus=" + oldAuditStatus,
                "auditStatus=" + auditDTO.getAuditResult()
        );

    }
}
