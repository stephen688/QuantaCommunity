package com.quanta.demo0.mq.outbox;

import com.quanta.demo0.entity.OutboxEvent;
import com.quanta.demo0.mq.message.OutboxRoute;
import com.quanta.demo0.properties.OutboxDispatchProperties;
import com.quanta.demo0.service.OutboxEventService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Outbox 定时发送器。
 *
 * 核心流程：
 * 1. 抢占 PENDING 或租约过期的 PROCESSING；
 * 2. 在数据库事务外发送 RabbitMQ；
 * 3. 根据 Confirm 和 Return 更新状态；
 * 4. 失败时安排下一次重试；
 * 5. 重试耗尽后进入 DEAD。
 */
@Slf4j
@Component
public class OutboxDispatcher {

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private OutboxEventService outboxEventService;

    @Autowired
    private OutboxRouteRegistry outboxRouteRegistry;

    @Autowired
    private OutboxDispatchProperties
            outboxDispatchProperties;

    /**
     * 每个应用进程拥有不同的实例 ID。
     */
    private final String instanceId =
            "outbox-" + UUID.randomUUID();

    /**
     * 上一次执行结束后，等待 fixedDelayMs，
     * 然后再执行下一次。
     */
    @Scheduled(
            fixedDelayString =
                    "${quanta.outbox.dispatch.fixed-delay-ms:1000}"
    )
    public void dispatch() {
        List<OutboxEvent> events =
                outboxEventService.claimBatch(instanceId);

        for (OutboxEvent event : events) {
            dispatchOne(event);
        }
    }

    /**
     * 发送单条 Outbox 事件。
     */
    private void dispatchOne(OutboxEvent event) {
        try {
            OutboxRoute route =
                    outboxRouteRegistry.resolve(event);

            CorrelationData correlationData =
                    new CorrelationData(
                            event.getEventId()
                    );

            rabbitTemplate.convertAndSend(
                    route.getExchange(),
                    route.getRoutingKey(),
                    route.getMessage(),
                    correlationData
            );

            CorrelationData.Confirm confirm =
                    correlationData
                            .getFuture()
                            .get(
                                    outboxDispatchProperties
                                            .getConfirmTimeoutSeconds(),
                                    TimeUnit.SECONDS
                            );

            /**
             * Confirm NACK：
             * RabbitMQ 没有接受消息。
             */
            if (!confirm.isAck()) {
                handleFailure(
                        event,
                        "RabbitMQ Confirm NACK："
                                + confirm.getReason()
                );
                return;
            }

            /**
             * Confirm ACK 但存在 ReturnedMessage：
             * 交换机收到了消息，
             * 但没有队列匹配这个路由键。
             */
            if (correlationData.getReturned() != null) {
                handleFailure(
                        event,
                        "RabbitMQ 消息未路由："
                                + correlationData
                                .getReturned()
                                .getReplyText()
                );
                return;
            }

            boolean updated =
                    outboxEventService.markSent(
                            event.getId(),
                            instanceId
                    );

            if (!updated) {
                log.warn(
                        "Outbox 已失去租约，不能标记 SENT，eventId={}",
                        event.getEventId()
                );
                return;
            }

            log.info(
                    "Outbox 发送成功，eventId={}, eventType={}",
                    event.getEventId(),
                    event.getEventType()
            );

        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();

            handleFailure(
                    event,
                    "等待 RabbitMQ Confirm 时线程被中断"
            );

        } catch (TimeoutException exception) {
            handleFailure(
                    event,
                    "等待 RabbitMQ Confirm 超时"
            );

        } catch (Exception exception) {
            handleFailure(
                    event,
                    exception.getClass().getSimpleName()
                            + "："
                            + exception.getMessage()
            );
        }
    }

    /**
     * 统一处理发送失败。
     */
    private void handleFailure(
            OutboxEvent event,
            String lastError
    ) {
        Integer currentRetryCount =
                event.getRetryCount() == null
                        ? 0
                        : event.getRetryCount();

        /**
         * retry_count 已经达到 6：
         * 说明额外重试次数已经耗尽。
         */
        if (currentRetryCount >=
                outboxDispatchProperties
                        .getMaxRetryCount()) {

            boolean updated =
                    outboxEventService.markDead(
                            event.getId(),
                            instanceId,
                            lastError
                    );

            if (updated) {
                log.error(
                        "Outbox 进入 DEAD，eventId={}, error={}",
                        event.getEventId(),
                        lastError
                );
            }

            return;
        }

        Integer nextRetryCount =
                currentRetryCount + 1;

        Long delaySeconds =
                resolveRetryDelay(
                        nextRetryCount
                );

        LocalDateTime nextRetryTime =
                LocalDateTime.now()
                        .plusSeconds(delaySeconds);

        boolean updated =
                outboxEventService.markRetry(
                        event.getId(),
                        instanceId,
                        nextRetryCount,
                        nextRetryTime,
                        lastError
                );

        if (updated) {
            log.warn(
                    "Outbox 等待重试，"
                            + "eventId={}, retryCount={}, "
                            + "nextRetryTime={}, error={}",
                    event.getEventId(),
                    nextRetryCount,
                    nextRetryTime,
                    lastError
            );
        }
    }

    /**
     * 根据第几次重试取得等待时间。
     */
    private Long resolveRetryDelay(
            Integer retryCount
    ) {
        List<Long> retryDelays =
                outboxDispatchProperties
                        .getRetryDelaysSeconds();

        int index = Math.min(
                retryCount - 1,
                retryDelays.size() - 1
        );

        return retryDelays.get(index);
    }
}
