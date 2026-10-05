package com.quanta.demo0.content.service.impl;

import com.quanta.demo0.search.service.TrendingCacheInvalidator;
import com.github.pagehelper.Page;
import com.github.pagehelper.PageHelper;
import com.quanta.demo0.moderation.enums.ModerationTargetType;
import com.quanta.demo0.platform.audit.constant.AdminAuditActionConstants;
import com.quanta.demo0.content.dto.ContentAdminQueryDTO;
import com.quanta.demo0.content.dto.ContentAuditDTO;
import com.quanta.demo0.platform.security.context.BaseContext;
import com.quanta.demo0.content.entity.Content;
import com.quanta.demo0.content.vo.ContentSnapshotVO;
import com.quanta.demo0.answer.vo.AnswerSnapshotVO;
import com.quanta.demo0.answer.service.AnswerCounterService;
import com.quanta.demo0.answer.service.AnswerCommandService;
import com.quanta.demo0.comment.service.CommentCommandService;
import com.quanta.demo0.notification.enums.NotificationType;
import com.quanta.demo0.content.exception.ContentFailedException;
import com.quanta.demo0.content.mapper.ContentMapper;
import com.quanta.demo0.notification.mq.message.NotificationEventMessage;
import com.quanta.demo0.notification.mq.producer.NotificationEventProducer;
import com.quanta.demo0.rag.vector.ContentVectorSyncService;
import com.quanta.demo0.platform.common.result.PageResult;
import com.quanta.demo0.platform.audit.service.AdminAuditRecorder;
import com.quanta.demo0.content.service.AdminContentService;
import com.quanta.demo0.interaction.service.ContentInteractionService;
import com.quanta.demo0.feed.service.ContentExposureService;
import com.quanta.demo0.content.service.ContentDetailCacheInvalidator;
import com.quanta.demo0.content.mq.producer.ContentEventProducer;
import com.quanta.demo0.search.mq.producer.SearchEventProducer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static com.quanta.demo0.platform.redis.constant.RedisConstants.*;

/**
 * 管理端内容服务实现类。
 *
 * 核心职责：
 * 1. 提供内容分页查询、人工审核、删除、举报处理等后台治理能力；
 * 2. 与内容曝光服务协同，控制帖子在推荐池、Feed、搜索索引中的可见性；
 * 3. 维护审核通知与举报处理状态，确保治理链路可追踪。
 *
 * 设计说明：
 * - 关键状态流转置于事务内，避免审核状态与外部副作用错位；
 * - 通过 ContentExposureService 复用"通过曝光/驳回下线"统一逻辑。
 *
 * ============================================================
 * 【为什么人工审核不复用 ContentAuditServiceImpl.approveContent？】
 * ============================================================
 * 机审那条路用 CAS（updateAuditStatusIfPending）把状态机锁死在
 * "只允许 PENDING → 通过/驳回"，防的是 MQ 重复消费和并发覆盖；
 * 管理端恰恰**需要突破这个限制**：已通过的帖子要能下架（1→2），
 * 被驳回的帖子要能翻案重新可见（2→1）—— 这是产品赋予管理员的权力。
 * 所以本类改用"读旧状态 → 直接 update → 按新旧状态差分补事件"的写法：
 * **代价是放弃 CAS 保护，换来完整的状态图**。
 * 重复点击的幂等不靠 CAS，靠"新旧状态相同 = 差分为 0，什么都不做"兜住。
 *
 * ============================================================
 * 【audit 方法是一个"状态差分器"，不是 if-else 流程图】
 * ============================================================
 * 骨架：oldAuditStatus（改之前的真实状态）× auditResult（管理员的决定）
 *   → 差分出"可见性是否变化" → 对称补齐所有下游：
 *   进池/出池（exposeApprovedContent/hideRejectedContent）、Feed UPSERT/DELETE、
 *   ES 对账事件、专业区回答联动校准、用户通知、缓存失效。
 * **事件的依据是状态差，不是动作本身** —— 同一决定重复提交时差分为 0，全部跳过。
 * 所有 Outbox 事件与状态更新在同一个事务提交，回滚时事件一起消失，不会出现
 * "状态没改、消息已飞"的幽灵事件（Outbox 模式，见 ContentCommandServiceImpl 的说明）。
 */
@Service
@Slf4j
public class AdminContentServiceImpl  implements AdminContentService {

    @Autowired
    private ContentMapper contentMapper;
    @Autowired
    private ContentInteractionService contentInteractionService;
    @Autowired
    private ContentVectorSyncService contentVectorSyncService;
    @Autowired
    private StringRedisTemplate stringRedisTemplate;
    @Autowired
    private AnswerCounterService answerCounterService;
    @Autowired
    private AnswerCommandService answerCommandService;
    @Autowired
    private CommentCommandService commentCommandService;
    @Autowired
    private ContentEventProducer contentEventProducer;
    @Autowired
    private SearchEventProducer searchEventProducer;
    @Autowired
    private NotificationEventProducer notificationEventProducer;
    @Autowired
    private ContentExposureService contentExposureService;
    @Autowired
    private TrendingCacheInvalidator trendingCacheInvalidator;
    @Autowired
    private ContentDetailCacheInvalidator contentDetailCacheInvalidator;

    @Autowired
    private AdminAuditRecorder adminAuditRecorder;


        /**
         * 分页查询内容列表
         * 执行流程：
         * 1. PageHelper.startPage() 开启分页
         * 2. 调用 Mapper 执行 SQL 查询
         * 3. 封装为 PageResult 返回
         * @param query 查询条件
         * @return 分页结果
         */
        @Override
        public PageResult pageQuery(ContentAdminQueryDTO query) {
            PageHelper.startPage(query.getPageNum(), query.getPageSize());
            Page<Content> page = contentMapper.pageAdmin(query);
            return new PageResult(page.getTotal(), page.getResult());
        }

        /**
         * 审核内容
         * 执行流程：
         * 1. 校验参数合法性
         * 2. 查询内容是否存在
         * 3. 记录原审核状态
         * 4. 更新审核状态
         * 5. 根据状态变化同步 ES 和向量库
         *    - 待审核 → 通过：同步到 ES 和向量库
         *    - 已通过 → 驳回：从 ES 和向量库删除
         *    - 已驳回 → 通过：同步到 ES 和向量库
         *
         * @param auditDTO 审核信息
         */
    @Override
    @Transactional
    public void audit(ContentAuditDTO auditDTO) {
        // 1. 校验参数
        if (auditDTO.getContentId() == null) {
            throw new ContentFailedException("内容 ID 不能为空");
        }
        if (auditDTO.getAuditResult() == null ||
                (auditDTO.getAuditResult() != 1 && auditDTO.getAuditResult() != 2)) {
            throw new ContentFailedException("审核结果不合法（1-通过 2-驳回）");
        }
        // 【安全边界】管理员能决定的只有"结果"（1/2）和驳回原因，
        // 请求体里没有、也不允许有标题/正文等任何内容字段 —— 帖子本体在这里不可篡改；
        // 值域也由服务端校验，而不是信任前端下拉框。

        // 2. 查询内容是否存在
        Content content = contentMapper.selectById(auditDTO.getContentId());
        if (content == null) {
            throw new ContentFailedException("内容不存在");
        }

        // 3. 记录原审核状态
        Integer oldAuditStatus = content.getAuditStatus();

        // 4. 更新审核状态
        // 【为什么这里不是 CAS？】见类头：管理端要支持 1→2、2→1 的回流，
        // updateAuditStatusIfPending 的 WHERE audit_status=0 会把这些合法流转全部挡死。
        // "读旧状态 → update"之间的并发窗口（状态被机审抢先改掉）后果有限：
        // 下面的差分按读到的旧状态计算，任何一次后续状态变化还会再对账 —— 最终一致兜底。
        Content updateContent = new Content();
        updateContent.setContentId(auditDTO.getContentId());
        updateContent.setAuditStatus(auditDTO.getAuditResult());
        updateContent.setUpdateTime(LocalDateTime.now());
        contentMapper.update(updateContent);

        // 三个合法流转：0→1 首次上架、1→2 下架、2→1 翻案。
        // 同状态重复提交（如 1→1）时 visibilityChanged=false，
        // 下面的曝光/Feed/搜索分支全部跳过 —— 这就是管理端操作的幂等实现。
        boolean visibilityChanged = (oldAuditStatus == 0 && auditDTO.getAuditResult() == 1)
                || (oldAuditStatus == 1 && auditDTO.getAuditResult() == 2)
                || (oldAuditStatus == 2 && auditDTO.getAuditResult() == 1);
        if (visibilityChanged) {
            // 先登记提交后失效；后续异常导致事务回滚时不会真正驱逐缓存。
            trendingCacheInvalidator.evictAfterCommit("admin-content-audit");
        }
        if (!oldAuditStatus.equals(auditDTO.getAuditResult())) {
            contentDetailCacheInvalidator.evictAfterCommit(
                    auditDTO.getContentId(),
                    "admin-content-audit"
            );
        }

        if (!oldAuditStatus.equals(auditDTO.getAuditResult())) {
            String triggerType = auditDTO.getAuditResult() == 1 ? "AUDIT_APPROVED" : "AUDIT_REJECTED";

            // 人工待审/驳回→通过也属于增量打标入口；仅可见内容，标签任务与审核同事务。
            if (auditDTO.getAuditResult() == 1 && Integer.valueOf(0).equals(content.getIsDeleted())) {
                contentEventProducer.createContentTopicTagEvent(auditDTO.getContentId());
            }

            // 帖子审核状态和 ES 校准事件在同一个事务中提交。
            searchEventProducer.createSearchReconcileEvent(ModerationTargetType.CONTENT.name(), auditDTO.getContentId(), triggerType);

            // 专业区帖子状态变化时，其已通过回答也需要根据父帖当前状态重新校准。
            if (content.getContentType() != null && content.getContentType() == 2) {
                List<AnswerSnapshotVO> answers = answerCounterService.getAnswerSnapshotsByQuestionId(auditDTO.getContentId());
                for (AnswerSnapshotVO answer : answers) {
                    searchEventProducer.createSearchReconcileEvent(ModerationTargetType.ANSWER.name(), answer.getAnswerId(), "PARENT_CONTENT_" + triggerType);
                }
            }
        }

        // 5. 根据状态变化同步曝光侧效应
        if (oldAuditStatus == 0 && auditDTO.getAuditResult() == 1) {
            content.setAuditStatus(auditDTO.getAuditResult());
            // 人工审核状态和 Feed UPSERT Outbox 在同一个事务中提交。
            contentEventProducer.createFeedUpsertEvent(content);
            contentExposureService.exposeApprovedContent(toSnapshot(content));
            log.info("审核通过（待审→通过），已执行曝光，contentId={}", auditDTO.getContentId());
        } else if (oldAuditStatus == 1 && auditDTO.getAuditResult() == 2) {
            // 已曝光帖子改为驳回时，可靠登记 Feed DELETE 事件。
            contentEventProducer.createFeedDeleteEvent(content);
            contentExposureService.hideRejectedContent(auditDTO.getContentId());
            log.info("审核驳回（通过→驳回），已清理曝光，contentId={}", auditDTO.getContentId());
        } else if (oldAuditStatus == 2 && auditDTO.getAuditResult() == 1) {
            // 驳回→通过是管理端独有的"翻案"流转，机审链路永远不会产生这种迁移：
            // 重新曝光 + Feed UPSERT，让帖子回到推荐池和 Feed。
            content.setAuditStatus(auditDTO.getAuditResult());
            contentEventProducer.createFeedUpsertEvent(content);
            contentExposureService.exposeApprovedContent(toSnapshot(content));
            log.info("审核通过（驳回→通过），已执行曝光，contentId={}", auditDTO.getContentId());
        }

        // ========== 新增：审核结果通知 ==========
// 只有审核状态发生变化时才发送通知
        if (!oldAuditStatus.equals(auditDTO.getAuditResult())) {
            String notifyContent = auditDTO.getAuditResult() == 1 ? "你的内容已审核通过" : "你的内容审核未通过";
            if (auditDTO.getAuditResult() == 2 && auditDTO.getRejectReason() != null) {
                notifyContent += "，原因：" + auditDTO.getRejectReason();
            }

            NotificationEventMessage auditNotification = NotificationEventMessage.builder()
                    .recipientUserId(content.getPublishUserId())
                    .actorUserId(null) // 系统通知，无具体触发者
                    .type(NotificationType.CONTENT_AUDIT_RESULT.getCode())
                    .content(notifyContent)
                    .payload(Map.of(
                            "contentId", auditDTO.getContentId(),
                            "auditResult", auditDTO.getAuditResult(),
                            "rejectReason", auditDTO.getRejectReason() != null ? auditDTO.getRejectReason() : ""
                    ))
                    .build();
            // 帖子审核状态和审核结果通知 Outbox 在同一个事务中提交。
            notificationEventProducer.createNotificationEvent(auditNotification, ModerationTargetType.CONTENT.name(), auditDTO.getContentId());
        }

    }

    //TODO: 后续可以抽取一个公共方法，专门处理内容删除的业务逻辑，deleteContent 和 deleteContentByAdmin 都调用这个公共方法，避免代码重复
    /**
     * 管理端删除内容
     * 执行流程：
     * 与 deleteContent 完全一致，唯一区别是跳过发布者权限校验
     *
     * 【为什么管理端敢跳过发布者校验？】
     * 用户侧（ContentCommandServiceImpl.deleteContent）必须比对 BaseContext 里的 userId；
     * 管理端入口在 Controller/拦截器层已完成管理员鉴权，服务层再校验发布者
     * 反而会挡住"删除任意违规帖"这个核心诉求 —— 鉴权在前，业务在后。
     *
     * 【删除本体是同一套"四层清理"】
     * 事实源软删 → Outbox 同事务 → 缓存失效（afterCommit）→ 投影清理（afterCommit），
     * 与用户侧逐行对应，分层讲解见 ContentCommandServiceImpl.deleteContent 的注释，
     * 这里不重复展开。
     *
     * @param contentId 内容 ID
     */
    @Override
    @Transactional
    public void deleteContent(Long contentId) {
        // 1. 参数校验
        if (contentId == null) {
            throw new ContentFailedException("contentId不能为空");
        }
        // 2. 查询内容是否存在
        Content content = contentMapper.selectById(contentId);
        if (content == null) {
            throw new ContentFailedException("内容不存在");
        }
        // 3. 跳过权限校验（管理端无需判断发布者）

        // 4. 物理删除内容图片
        contentMapper.deleteContentImages(contentId);
        // 5. 物理删除内容点赞记录
        contentInteractionService.deleteByContentId(contentId);
        // 评论图片、点赞关联与软删除仍加入当前内容删除事务。
        commentCommandService.deleteByContentId(contentId);
        // 删除前先记住已经进入 ES 的回答，软删除后由校准事件清理回答索引。
        List<Long> answerIds = content.getContentType() != null && content.getContentType() == 2
                ? answerCommandService.deleteByQuestionId(contentId)
                : List.of();

        // 9. 如果是专业区，删除专业区内容
        // 10. 软删除内容本身
        contentMapper.softDeleteContent(contentId);

        // 删除状态和 Feed DELETE Outbox 在同一个事务中提交。
        contentEventProducer.createFeedDeleteEvent(content);

        // 帖子和关联回答删除状态与 ES 校准事件一起提交。
        searchEventProducer.createSearchReconcileEvent(ModerationTargetType.CONTENT.name(), contentId, "DELETE");
        for (Long answerId : answerIds) {
            searchEventProducer.createSearchReconcileEvent(ModerationTargetType.ANSWER.name(), answerId, "PARENT_CONTENT_DELETE");
        }

        trendingCacheInvalidator.evictAfterCommit("admin-content-delete:" + contentId);
        contentDetailCacheInvalidator.evictAfterCommit(contentId, "admin-content-delete");

        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    // 11. 删最新推荐流
                    stringRedisTemplate.opsForZSet().remove(RECOMMEND_ALL_KEY, contentId.toString());
                    if (content.getContentType() != null) {
                        if (content.getContentType() == 1) {
                            stringRedisTemplate.opsForZSet().remove(RECOMMEND_LIFE_KEY, contentId.toString());
                        } else if (content.getContentType() == 2) {
                            stringRedisTemplate.opsForZSet().remove(RECOMMEND_PROFESSIONAL_KEY, contentId.toString());
                        }
                    }
                    // 11.2 删热度推荐流
                    stringRedisTemplate.opsForZSet().remove(RECOMMEND_HOT_ALL_KEY, contentId.toString());
                    if (content.getContentType() != null) {
                        if (content.getContentType() == 1) {
                            stringRedisTemplate.opsForZSet().remove(RECOMMEND_HOT_LIFE_KEY, contentId.toString());
                        } else if (content.getContentType() == 2) {
                            stringRedisTemplate.opsForZSet().remove(RECOMMEND_HOT_PROFESSIONAL_KEY, contentId.toString());
                        }
                    }

                    // 12. 删除redis中的点赞记录
                    String likeKey = CONTENT_LIKED_KEY + contentId;
                    stringRedisTemplate.delete(likeKey);

                    // 13. 删除redis中的收藏记录
                    String collectKey = CONTENT_COLLECT_KEY + contentId;
                    stringRedisTemplate.delete(collectKey);

                    // 16. 删除向量库
                    contentVectorSyncService.deleteByContentId(contentId);

                    log.info("管理端删除内容成功，contentId={}", contentId);
                }
            });
        }
    }

    private ContentSnapshotVO toSnapshot(Content content) {
        return ContentSnapshotVO.builder()
                .contentId(content.getContentId())
                .contentType(content.getContentType())
                .title(content.getTitle())
                .content(content.getContent())
                .tags(content.getTags())
                .publishUserId(content.getPublishUserId())
                .auditStatus(content.getAuditStatus())
                .isDeleted(content.getIsDeleted())
                .createTime(content.getCreateTime())
                .updateTime(content.getUpdateTime())
                .likedCount(content.getLiked())
                .commentCount(content.getCommentCount())
                .collectCount(content.getCollectCount())
                .build();
    }
}
