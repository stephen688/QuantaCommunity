package com.quanta.demo0.search.mq.producer;

import com.quanta.demo0.content.exception.ContentFailedException;
import com.quanta.demo0.moderation.enums.ModerationTargetType;
import com.quanta.demo0.platform.mq.enums.OutboxEventType;
import com.quanta.demo0.platform.mq.exception.OutboxInsertFailedException;
import com.quanta.demo0.platform.mq.producer.OutboxEventAppender;
import com.quanta.demo0.search.mq.message.SearchReconcileMessage;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 搜索域可靠事件生产者：构造内容或回答索引校准消息。
 *
 * ============================================================
 * 【为什么发事件要走 Outbox，而不是直接发 RabbitMQ？】
 * ============================================================
 * 调用方（内容/回答/评论等域）在业务写事务里调用本方法：
 * {@link OutboxEventAppender#append} 只是把事件插入 outbox 表，
 * 与业务变更同事务提交——**业务成功则事件必在，事务回滚则事件消失，
 * 不会出现"库改了消息丢了"或"消息发了事务却回滚"的幽灵对账**。
 * 真正投递到 search.reconcile.exchange 由 platform/mq 的
 * OutboxDispatcher 异步完成（路由规则在 OutboxRouteRegistry 中
 * 按 SEARCH_RECONCILE_REQUESTED 映射），失败还能按 outbox 重试。
 */
@Service
@RequiredArgsConstructor
public class SearchEventProducer {

    private final OutboxEventAppender outboxEventAppender;

    /**
     * 在业务写事务中追加 ES 校准事件。
     *
     * 【坑 1】方法自带 @Transactional，但通常加入的是调用方的既有事务
     * （默认传播 REQUIRED），保证事件与业务变更同生共死。
     * 【坑 2】triggerType 只用于排查日志，不影响对账行为——对账永远以
     * MySQL 当前状态为准，事件本身不携带"应该怎么改"的指令。
     *
     * @return 生成的 eventId（Outbox 与后续 Inbox 幂等的锚点）
     */
    @Transactional
    public String createSearchReconcileEvent(String targetType, Long targetId, String triggerType) {
        // 入口即校验：不合法的事件宁可在这里抛掉，也不能流进 MQ 变成消费侧的死信。
        if (!StringUtils.hasText(targetType) || targetId == null) {
            throw new ContentFailedException("ES 校准事件缺少必要信息");
        }
        // 与消费侧 SearchReconcileServiceImpl 支持的类型保持一一对应。
        if (!ModerationTargetType.CONTENT.name().equals(targetType)
                && !ModerationTargetType.ANSWER.name().equals(targetType)) {
            throw new ContentFailedException("ES 校准事件只支持 CONTENT 或 ANSWER");
        }

        String eventId = UUID.randomUUID().toString();
        LocalDateTime occurredAt = LocalDateTime.now();
        SearchReconcileMessage message = SearchReconcileMessage.builder()
                .eventId(eventId)
                .eventType(OutboxEventType.SEARCH_RECONCILE_REQUESTED.getCode())
                .occurredAt(occurredAt)
                .targetType(targetType)
                .targetId(targetId)
                .triggerType(triggerType)
                .retryCount(0)
                .build();
        try {
            // 只追加 Outbox 行，不直接发 MQ；payload 超过 32KB 会被平台层拒绝。
            return outboxEventAppender.append(
                    eventId,
                    OutboxEventType.SEARCH_RECONCILE_REQUESTED.getCode(),
                    targetType,
                    targetId,
                    message);
        } catch (OutboxEventAppender.PayloadTooLargeException exception) {
            // 平台层契约：outbox payload 上限 32KB；异常文案沿用历史口径。
            throw new ContentFailedException("审核任务超过 32 KB，请缩短内容或图片地址");
        } catch (OutboxInsertFailedException exception) {
            throw new ContentFailedException("创建 ES 校准事件失败");
        }
    }
}
