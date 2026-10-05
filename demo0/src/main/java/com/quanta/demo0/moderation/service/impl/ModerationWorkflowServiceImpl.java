package com.quanta.demo0.moderation.service.impl;

import com.quanta.demo0.moderation.enums.ModerationDecision;
import com.quanta.demo0.moderation.mq.message.ModerationTaskMessage;
import com.quanta.demo0.moderation.mq.producer.ModerationProducer;
import com.quanta.demo0.moderation.properties.AliyunModerationProperties;
import com.quanta.demo0.moderation.result.ModerationResult;
import com.quanta.demo0.moderation.result.ModerationWorkflowResult;
import com.quanta.demo0.moderation.service.ContentModerationService;
import com.quanta.demo0.moderation.service.ModerationWorkflowService;
import com.quanta.demo0.answer.service.AnswerAuditService;
import com.quanta.demo0.comment.service.CommentAuditService;
import com.quanta.demo0.content.service.ContentAuditService;
import com.quanta.demo0.platform.mq.enums.InboxAcquireResult;
import com.quanta.demo0.platform.mq.service.InboxEventService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 审核可靠消费工作流。
 *
 * <p>工作流返回传输层动作，但不直接操作 RabbitMQ channel。审核目标更新与
 * Inbox SUCCESS 标记在同一个事务方法中完成；监听器只有在工作流返回 ACK 后才确认消息。</p>
 *
 * ============================================================
 * 【一次 process 调用的完整地图】
 * ============================================================
 * 上游：发布/回答/评论事务里登记的 Outbox 事件（MODERATION_REQUESTED），
 * 由 OutboxDispatcher 投递到 moderation.queue（路由见 OutboxRouteRegistry）。
 * 本类是消费端编排者：
 *   acquire 抢占 → moderationService.moderate 机审
 *   → dispatchResult 更新业务状态 + markSuccess（同事务）→ ACK；
 * 任一步失败 → markRetry 延迟重试，或耗尽重试后 markDead → 死信队列。
 *
 * ============================================================
 * 【Inbox 抢占的四种结果 → 四种消息动作】
 * ============================================================
 * 判重键是 consumerName("moderation-consumer") + eventId，**与业务 ID 无关**，
 * 同一事件被 RabbitMQ 重复投递时必然命中同一行 Inbox：
 * - ALREADY_SUCCESS → ACK：审过的事件重复投递，静默吞掉（幂等消费的核心收益）；
 * - BUSY            → 转发到延迟重试队列再 ACK：其他实例 60 秒租约期内正在处理，
 *                     立即重投只会原地撞锁（见 sendBusyMessageToRetry）；
 * - DEAD            → DEAD：Inbox 已判死，直接 nack 进死信队列，不再重试；
 * - ACQUIRED        → 真正执行审核（processAcquiredMessage）。
 * （四种结果的判定细节见 InboxEventServiceImpl.acquireEvent。）
 *
 * ============================================================
 * 【重试语义：什么算失败、重试什么、重试几次】
 * ============================================================
 * 算失败：moderate 返回 ERROR（云 API 没审成）或处理过程抛异常——
 *         机审业务结论 REJECT/MANUAL 不算失败，不重试；
 * 重试什么：整条任务消息经 moderation.retry.queue 延迟 60 秒回到主队列
 *          （**延迟的真实落点是队列 x-message-ttl=60000，见 ModerationMQConfig**，
 *          常量 RETRY_DELAY_SECONDS 只是换算出下次可重取时间写进 Inbox 的
 *          locked_until 字段，供记录与观察），
 *          retryCount 计数随消息携带，Inbox 行同步记 RETRYING；
 * 重试几次：quanta.moderation.max-retry-count=3，耗尽后 saveFailedRecord
 *          （decision=ERROR、taskStatus=FAILED，供管理端兜底）+ markDead，
 *          消息 nack 进死信队列。**失败记录只落一次库，就在耗尽那一刻。**
 *
 * ============================================================
 * 【markSuccess 为什么可能返回 false？——租约语义】
 * ============================================================
 * markSuccess/markRetry/markDead 的 SQL 都带
 * WHERE status='PROCESSING' AND locked_by=本实例（见 InboxEventMapper.xml）：
 * 处理超过 60 秒租约、被其他实例抢占后，UPDATE 0 行、返回 false。
 * 此时**本实例绝不能再写业务状态**——抛异常让事务整体回滚，
 * 这是"抢占成功才允许推进状态"的最后一道防线。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ModerationWorkflowServiceImpl
        implements ModerationWorkflowService {

    private static final String CONSUMER_NAME = "moderation-consumer";

    private static final long RETRY_DELAY_SECONDS = 60L;

    private final String instanceId = "moderation-" + UUID.randomUUID();

    private final ContentModerationService moderationService;

    private final ContentAuditService contentAuditService;

    private final AnswerAuditService answerAuditService;

    private final CommentAuditService commentAuditService;

    private final InboxEventService inboxEventService;

    private final ModerationProducer moderationProducer;

    private final AliyunModerationProperties moderationProperties;

    private final PlatformTransactionManager transactionManager;

    /**
     * 可靠消费入口（全景与重试语义见类注释）。
     * 【坑】eventId 缺失直接返回 DEAD：Inbox 的判重键就是 consumerName+eventId，
     * 没有它既无法登记也无法幂等，重试多少次都无意义——尽快进死信队列等人看。
     */
    @Override
    public ModerationWorkflowResult process(ModerationTaskMessage task) {
        if (task == null || !StringUtils.hasText(task.getEventId())) {
            if (task == null) {
                log.error("审核消息为空，拒绝进入可靠消费链路");
            } else {
                log.error("审核消息缺少 eventId，拒绝进入可靠消费链路，targetType={}, targetId={}",
                        task.getTargetType(), task.getTargetId());
            }
            return ModerationWorkflowResult.DEAD;
        }

        try {
            // 抢占事件, 60 秒租约
            InboxAcquireResult acquireResult = inboxEventService.acquire(
                    CONSUMER_NAME,
                    instanceId,
                    task
            );

            // 处理抢占结果, 分四种情况:
            // ALREADY_SUCCESS: 已处理成功，直接 ACK
            // BUSY: 正在其他实例处理，进入重试队列
            // DEAD: 已进入 DEAD，转入死信队列
            // ACQUIRED: 已抢到租约，真正执行审核
                       return switch (acquireResult) {
                case ALREADY_SUCCESS -> {
                    log.info("审核事件已经处理成功，直接 ACK，eventId={}", task.getEventId());
                    yield ModerationWorkflowResult.ACK;
                }
                case BUSY -> {
                    log.info("审核事件正在被其他实例处理，进入重试队列，eventId={}", task.getEventId());
                    yield sendBusyMessageToRetry(task);
                }
                case DEAD -> {
                    log.error("审核事件已经进入 DEAD，转入死信队列，eventId={}", task.getEventId());
                    yield ModerationWorkflowResult.DEAD;
                }
                case ACQUIRED -> processAcquiredMessage(task);
            };
        } catch (Exception exception) {
            log.error("审核消息抢占异常，eventId={}", task.getEventId(), exception);
            return ModerationWorkflowResult.REQUEUE;
        }
    }

    /**
     * 已抢到租约后的真正处理。
     * 【两条失败路径在此汇合】机审返回 ERROR（业务层面的"没审成"）与
     * 处理过程抛异常都进 handleProcessingFailure——对重试机制而言二者等价。
     */
    private ModerationWorkflowResult processAcquiredMessage(
            ModerationTaskMessage task
    ) {
        try {
            log.info("开始处理审核任务，eventId={}, targetType={}, targetId={}",
                    task.getEventId(), task.getTargetType(), task.getTargetId());

            ModerationResult result = moderationService.moderate(task);

            if (result.getDecision() == ModerationDecision.ERROR) {
                return handleProcessingFailure(
                        task,
                        result,
                        result.getRejectReason()
                );
            }

            applyResultAndMarkSuccess(task, result);

            log.info("审核任务处理成功，eventId={}, targetId={}",
                    task.getEventId(), task.getTargetId());
            return ModerationWorkflowResult.ACK;
        } catch (Exception exception) {
            log.error("审核任务处理异常，eventId={}, targetId={}",
                    task.getEventId(), task.getTargetId(), exception);

            return handleProcessingFailure(
                    task,
                    null,
                    exception.getClass().getSimpleName() + "：" + exception.getMessage()
            );
        }
    }

    /**
     * 审核目标更新和 Inbox SUCCESS 必须作为一个事务提交。
     *
     * 【为什么用 TransactionTemplate 而不是 @Transactional？】
     * 这是私有方法，Spring 声明式事务基于代理，**内部调用 + 私有方法上的
     * @Transactional 不生效**；要精确圈定"dispatchResult + markSuccess 同事务"
     * 这段边界，编程式事务最直接。（对照实现：ModerationResultServiceImpl
     * 把同样逻辑抽成 public 方法，就能用方法级 @Transactional。）
     */
    private void applyResultAndMarkSuccess(
            ModerationTaskMessage task,
            ModerationResult result
    ) {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            dispatchResult(task, result);

            boolean updated = inboxEventService.markSuccess(
                    CONSUMER_NAME,
                    task.getEventId(),
                    instanceId
            );

            if (!updated) {
                throw new IllegalStateException("Inbox 已失去处理权，不能标记 SUCCESS");
            }
        });
    }

    /**
     * 按 targetType 路由到对应域，把 decision 翻译成该域的状态迁移。
     * 【下游差异速记】PASS→approveXxx（CAS 过审 + Feed/ES/推荐池/通知等曝光副作用）；
     * REJECT→rejectXxx（CAS 驳回 + ES 对账 + 通知作者，被驳内容从未曝光故无撤回动作）；
     * MANUAL→什么都不改（见各 dispatchXxxDecision）。
     * 各域实现见 ContentAuditService / AnswerAuditService / CommentAuditService；
     * ERROR 决策到不了这里（processAcquiredMessage 已拦截），default 抛异常是防御性兜底。
     */
    private void dispatchResult(
            ModerationTaskMessage task,
            ModerationResult result
    ) {
        ModerationDecision decision = result.getDecision();
        Long targetId = task.getTargetId();

        switch (task.getTargetType()) {
            case CONTENT -> dispatchContentDecision(targetId, decision, result);
            case ANSWER -> dispatchAnswerDecision(targetId, decision, result);
            case COMMENT -> dispatchCommentDecision(targetId, decision, result);
        }
    }

    /**
     * 机审结论 → 帖子状态机。
     * 【MANUAL 分支只打日志】帖子留在待审状态等人工裁决，机审证据经
     * AdminModerationService 供管理端查询——转人工不是"再发条消息"，
     * 而是靠"不改状态 + 留记录"这两个动作完成的。
     */
    private void dispatchContentDecision(
            Long contentId,
            ModerationDecision decision,
            ModerationResult result
    ) {
        switch (decision) {
            case PASS -> contentAuditService.approveContent(contentId);
            case REJECT -> contentAuditService.rejectContent(contentId, result.getRejectReason());
            case MANUAL -> log.info("帖子审核疑似，转人工，contentId={}", contentId);
            default -> throw new IllegalStateException("不能处理的审核结果：" + decision);
        }
    }

    /** 回答版决策分发，语义同 dispatchContentDecision（MANUAL 同样只留痕不改状态）。 */
    private void dispatchAnswerDecision(
            Long answerId,
            ModerationDecision decision,
            ModerationResult result
    ) {
        switch (decision) {
            case PASS -> answerAuditService.approveAnswer(answerId);
            case REJECT -> answerAuditService.rejectAnswer(answerId, result.getRejectReason());
            case MANUAL -> log.info("回答审核疑似，转人工，answerId={}", answerId);
            default -> throw new IllegalStateException("不能处理的审核结果：" + decision);
        }
    }

    /** 评论版决策分发，语义同 dispatchContentDecision。 */
    private void dispatchCommentDecision(
            Long commentId,
            ModerationDecision decision,
            ModerationResult result
    ) {
        switch (decision) {
            case PASS -> commentAuditService.approveComment(commentId);
            case REJECT -> commentAuditService.rejectComment(commentId, result.getRejectReason());
            case MANUAL -> log.info("评论审核疑似，转人工，commentId={}", commentId);
            default -> throw new IllegalStateException("不能处理的审核结果：" + decision);
        }
    }

    /**
     * 统一失败处理：重试未耗尽 → 安排延迟重试；耗尽 → 落失败记录 + Inbox DEAD。
     *
     * 【一个反直觉的点】安排完重试后返回的是 ACK 而不是 REQUEUE——
     * 重试已经通过 sendRetryTask 换成"新消息"进了 60 秒延迟队列，
     * 当前这条消息"被消费一次"的使命已完成，可以确认；
     * 若返回 REQUEUE，原消息立即重投，重试会翻倍。
     * 【markRetry/markDead 返回 false】租约丢失（处理超 60 秒被抢），
     * 抛异常走 catch → REQUEUE，让抢到租约的实例处理，本实例不越权。
     */
    private ModerationWorkflowResult handleProcessingFailure(
            ModerationTaskMessage task,
            ModerationResult result,
            String lastError
    ) {
        int currentRetryCount = task.getRetryCount() == null ? 0 : task.getRetryCount();
        int maxRetryCount = moderationProperties.getMaxRetryCount();

        try {
            if (currentRetryCount >= maxRetryCount) {
                moderationService.saveFailedRecord(task, result);

                boolean updated = inboxEventService.markDead(
                        CONSUMER_NAME,
                        task.getEventId(),
                        instanceId,
                        lastError
                );

                if (!updated) {
                    throw new IllegalStateException("Inbox 已失去处理权，不能标记 DEAD");
                }

                log.error("审核事件进入 DEAD，eventId={}, retryCount={}",
                        task.getEventId(), currentRetryCount);
                return ModerationWorkflowResult.DEAD;
            }

            int nextRetryCount = currentRetryCount + 1;
            LocalDateTime nextRetryTime = LocalDateTime.now().plusSeconds(RETRY_DELAY_SECONDS);

            boolean updated = inboxEventService.markRetry(
                    CONSUMER_NAME,
                    task.getEventId(),
                    instanceId,
                    nextRetryCount,
                    nextRetryTime,
                    lastError
            );

            if (!updated) {
                throw new IllegalStateException("Inbox 已失去处理权，不能标记 RETRYING");
            }

            task.setRetryCount(nextRetryCount);

            if (!moderationProducer.sendRetryTask(task)) {
                return ModerationWorkflowResult.REQUEUE;
            }

            log.warn("审核事件等待重试，eventId={}, retryCount={}, nextRetryTime={}",
                    task.getEventId(), nextRetryCount, nextRetryTime);
            return ModerationWorkflowResult.ACK;
        } catch (Exception exception) {
            log.error("审核失败状态处理异常，eventId={}", task.getEventId(), exception);
            return ModerationWorkflowResult.REQUEUE;
        }
    }

    /**
     * BUSY（其他实例租约处理中）时不 nack 原消息——立即重投还会撞同一把锁；
     * 把消息送进 60 秒延迟的重试队列，等租约大概率释放后再回到主队列。
     */
    private ModerationWorkflowResult sendBusyMessageToRetry(
            ModerationTaskMessage task
    ) {
        try {
            return moderationProducer.sendRetryTask(task)
                    ? ModerationWorkflowResult.ACK
                    : ModerationWorkflowResult.REQUEUE;
        } catch (Exception exception) {
            log.error("繁忙消息进入重试队列失败，eventId={}", task.getEventId(), exception);
            return ModerationWorkflowResult.REQUEUE;
        }
    }
}
