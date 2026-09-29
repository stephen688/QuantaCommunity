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
 */
@Service
@RequiredArgsConstructor
public class SearchEventProducer {

    private final OutboxEventAppender outboxEventAppender;

    /**
     * 在业务写事务中追加 ES 校准事件。
     */
    @Transactional
    public String createSearchReconcileEvent(String targetType, Long targetId, String triggerType) {
        if (!StringUtils.hasText(targetType) || targetId == null) {
            throw new ContentFailedException("ES 校准事件缺少必要信息");
        }
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
            return outboxEventAppender.append(
                    eventId,
                    OutboxEventType.SEARCH_RECONCILE_REQUESTED.getCode(),
                    targetType,
                    targetId,
                    message);
        } catch (OutboxEventAppender.PayloadTooLargeException exception) {
            throw new ContentFailedException("审核任务超过 32 KB，请缩短内容或图片地址");
        } catch (OutboxInsertFailedException exception) {
            throw new ContentFailedException("创建 ES 校准事件失败");
        }
    }
}
