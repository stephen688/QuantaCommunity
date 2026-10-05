package com.quanta.demo0.search.mq.consumer;

import com.quanta.demo0.search.config.SearchMQConfig;
import com.quanta.demo0.platform.mq.enums.InboxAcquireResult;
import com.quanta.demo0.search.mq.message.SearchReconcileMessage;
import com.quanta.demo0.search.mq.producer.SearchReconcileProducer;
import com.quanta.demo0.platform.mq.service.InboxEventService;
import com.quanta.demo0.search.service.SearchReconcileService;
import com.rabbitmq.client.Channel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Elasticsearch 索引校准消费者。
 *
 * 职责：
 * 1. 使用 Inbox 防止同一事件重复处理；
 * 2. 调用 Service 根据 MySQL 当前状态校准 ES；
 * 3. 处理重试、死信和 RabbitMQ ACK。
 *
 * ============================================================
 * 【为什么需要"对账"消费者？在业务代码里同步写 ES 不行吗？】
 * ============================================================
 * MySQL 与 ES 是两套独立存储，任何"先写库再同步写 ES"的双写都会因
 * 宕机、网络抖动、并发乱序而漏更新，还会把 ES 故障传染给主流程。
 * 本消费者是**最终一致性的兜底**：事件只携带 targetType + targetId，
 * 消费时重新查询 MySQL 当前状态，由 Service 决定 upsert 还是 delete——
 * 无论此前丢过多少次增量更新，一次对账就能把 ES 拉回与 MySQL 一致。
 * 审核通过/驳回、删除、点赞、收藏、评论变化等事件最终都汇到这里
 * （triggerType 见 SearchReconcileMessage）。
 *
 * ============================================================
 * 【为什么用手动 ack + Inbox 四态，而不是自动 ack？】
 * ============================================================
 * RabbitMQ 是至少一次投递，重复与重试不可避免。消费前先在 Inbox 表
 * （platform/mq）抢占该 eventId，租约 60 秒，四种结果对应四种走向：
 * - ALREADY_SUCCESS：重复消息，直接 ACK 丢弃，不再碰业务；
 * - BUSY：其他实例持有租约 → 转 60s 延迟重试队列稍后再试；
 * - DEAD：Inbox 已判死 → 转死信队列，供人工排查；
 * - ACQUIRED：本实例抢到处理权 → 业务成功后 markSuccess 再 ACK。
 * markSuccess 是按租约持有者（instanceId）的条件更新：返回 false 说明
 * 租约已易主（当前实例处理太久被接管），必须放弃本次结果并 nack 重回队列。
 * 业务失败最多重试 3 次（retryCount 随消息传递），超过即 markDead + 死信。
 */
@Component
@Slf4j
public class SearchReconcileConsumer {

    /** Inbox 表的消费者标识，与 instanceId 一起构成租约的归属键。 */
    private static final String CONSUMER_NAME = "search-reconcile-consumer";
    /** 业务失败的最大重试次数，超过即 markDead 转死信；与消息里的 retryCount 配套。 */
    private static final int MAX_RETRY_COUNT = 3;
    /** 重试间隔秒数，与重试队列的 x-message-ttl（60000ms）保持一致，改一处必须同步改另一处。 */
    private static final long RETRY_DELAY_SECONDS = 60L;

    /**
     * 当前应用实例的唯一标识。
     * 多个后端实例同时消费时，每个实例都不同。
     * 【租约】Inbox 的抢占与 markSuccess/markRetry/markDead 都按此 ID 做条件更新，
     * 谁抢到租约谁才有资格推进状态。
     */
    private final String instanceId = "search-reconcile-" + UUID.randomUUID();

    @Autowired
    private InboxEventService inboxEventService;

    @Autowired
    private SearchReconcileService searchReconcileService;

    @Autowired
    private SearchReconcileProducer searchReconcileProducer;

    /**
     * 对账消息主入口（手动 ack 模式）。
     * 【坑】全方法必须保证每条消息恰好走一次 ack 或 nack：ack = 消息从队列移除，
     * nack(requeue=true) = 立刻重回主队列。任何分支漏 ack 都会导致消息在
     * 连接断开后无限重投，所以收尾统一收敛到 ackQuietly / nackAndRequeue。
     */
    @RabbitListener(queues = SearchMQConfig.SEARCH_RECONCILE_QUEUE)
    public void handleSearchReconcileMessage(SearchReconcileMessage message, Message mqMessage, Channel channel) {
        long deliveryTag = mqMessage.getMessageProperties().getDeliveryTag();

        // V4 是新队列，不需要兼容没有 eventId 的历史消息。
        if (!StringUtils.hasText(message.getEventId())) {
            log.error("ES 校准消息缺少 eventId，message={}", message);
            sendInvalidMessageToDead(message, channel, deliveryTag);
            return;
        }

        try {
            // 抢占 Inbox：同一 eventId 在表里有唯一状态，天然拦截多实例并发重复消费。
            InboxAcquireResult acquireResult = inboxEventService.acquire(CONSUMER_NAME, instanceId, message);

            switch (acquireResult) {
                case ALREADY_SUCCESS -> {
                    // 相同事件之前已经成功处理，不能再次操作业务。
                    channel.basicAck(deliveryTag, false);
                    log.info("ES 校准事件已经处理成功，直接 ACK，eventId={}", message.getEventId());
                }

                case BUSY -> {
                    // 其他实例仍然持有处理租约，当前消息进入延迟重试队列。
                    sendBusyMessageToRetry(message, channel, deliveryTag);
                }

                case DEAD -> {
                    // Inbox 已经是 DEAD，不再执行业务，重新送入死信队列供管理员排查。
                    sendDeadMessage(message, channel, deliveryTag);
                }

                case ACQUIRED -> {
                    // 当前实例成功取得处理权。
                    processAcquiredMessage(message, channel, deliveryTag);
                }
            }
        } catch (Exception e) {
            // Inbox 抢占本身出异常（如 DB 抖动）：状态未知，只能 nack 重回队列再试，
            // 不能贸然 ack（可能丢消息）也不能转死信（事件本身可能是健康的）。
            log.error("ES 校准消息抢占异常，eventId={}", message.getEventId(), e);
            nackAndRequeue(channel, deliveryTag);
        }
    }

    /**
     * ACQUIRED 分支：本实例持有租约，执行对账业务。
     * 顺序铁律：先业务（校准 ES）、后 markSuccess、最后 ACK——
     * 任何一步失败都进入 {@link #handleProcessingFailure}，保证"至少一次业务效果"：
     * 最坏情况是业务已成功但 markSuccess 前宕机，消息重投后由于对账本身幂等
     * （同文档 ID 覆盖/删除），重复执行也不会产生脏数据。
     */
    private void processAcquiredMessage(SearchReconcileMessage message, Channel channel, long deliveryTag) {
        try {
            // Service 会重新查询 MySQL，决定 ES 应该 upsert 还是 delete。
            searchReconcileService.reconcileSearchIndex(message.getTargetType(), message.getTargetId());

            // ES 成功后再把 Inbox 标记为 SUCCESS。
            // markSuccess 按租约持有者条件更新：返回 false 说明租约已被其他实例接管，
            // 本实例的处理结果作废，抛异常走 nack，由新持有者重新对账（业务幂等，安全）。
            if (!inboxEventService.markSuccess(CONSUMER_NAME, message.getEventId(), instanceId)) {
                throw new IllegalStateException("ES 校准 Inbox 已失去处理权，不能标记 SUCCESS");
            }

            channel.basicAck(deliveryTag, false);
            log.info(
                    "ES 校准消息处理成功，eventId={}, targetType={}, targetId={}",
                    message.getEventId(),
                    message.getTargetType(),
                    message.getTargetId()
            );
        } catch (Exception e) {
            log.error("ES 校准消息处理失败，eventId={}", message.getEventId(), e);
            handleProcessingFailure(message, e, channel, deliveryTag);
        }
    }

    /**
     * 业务处理失败的分流：看消息里的 retryCount 决定"再试一次"还是"判死"。
     * 【重试方式】不是原地 nack 重投（会立刻失败、形成热循环），而是把 retryCount+1、
     * 约定 60s 后再试（nextRetryTime 记录到 Inbox），并经重试队列的 TTL 死信机制
     * 实现延迟（见 SearchMQConfig 的 retry 队列）。
     */
    private void handleProcessingFailure(
            SearchReconcileMessage message,
            Exception exception,
            Channel channel,
            long deliveryTag
    ) {
        int currentRetryCount = message.getRetryCount() == null ? 0 : message.getRetryCount();
        String lastError = exception.getClass().getSimpleName() + "：" + exception.getMessage();

        try {
            if (currentRetryCount >= MAX_RETRY_COUNT) {
                // 超过 3 次：Inbox 判死（防止毒消息无限重试拖垮消费者），消息进死信队列留档排查。
                boolean updated = inboxEventService.markDead(
                        CONSUMER_NAME,
                        message.getEventId(),
                        instanceId,
                        lastError
                );

                if (!updated) {
                    throw new IllegalStateException("ES 校准 Inbox 已失去处理权，不能标记 DEAD");
                }

                sendDeadMessage(message, channel, deliveryTag);
                return;
            }

            int nextRetryCount = currentRetryCount + 1;
            LocalDateTime nextRetryTime = LocalDateTime.now().plusSeconds(RETRY_DELAY_SECONDS);

            boolean updated = inboxEventService.markRetry(
                    CONSUMER_NAME,
                    message.getEventId(),
                    instanceId,
                    nextRetryCount,
                    nextRetryTime,
                    lastError
            );

            if (!updated) {
                throw new IllegalStateException("ES 校准 Inbox 已失去处理权，不能标记 RETRYING");
            }

            message.setRetryCount(nextRetryCount);
            // retryCount 随消息体一起走：重试消息再失败时据此判断是否达到上限。

            if (searchReconcileProducer.sendRetryTask(message)) {
                // 重试消息已经保存到 RabbitMQ，当前原消息可以 ACK。
                channel.basicAck(deliveryTag, false);
            } else {
                // 重试消息发送失败，原消息不能丢，重新放回主队列。
                nackAndRequeue(channel, deliveryTag);
            }
        } catch (Exception e) {
            log.error("ES 校准失败状态处理异常，eventId={}", message.getEventId(), e);
            nackAndRequeue(channel, deliveryTag);
        }
    }

    /**
     * BUSY 分支：别的实例正持有租约。本消息转入 60s 延迟重试队列，
     * 到期经死信机制回主队列——既不重复处理，也不丢消息。
     */
    private void sendBusyMessageToRetry(
            SearchReconcileMessage message,
            Channel channel,
            long deliveryTag
    ) {
        if (searchReconcileProducer.sendRetryTask(message)) {
            ackQuietly(channel, deliveryTag);
        } else {
            nackAndRequeue(channel, deliveryTag);
        }
    }

    /** DEAD 分支：Inbox 已判死的事件转发到死信队列留档，供人工排查后手工补偿。 */
    private void sendDeadMessage(
            SearchReconcileMessage message,
            Channel channel,
            long deliveryTag
    ) {
        if (searchReconcileProducer.sendDeadTask(message)) {
            ackQuietly(channel, deliveryTag);
        } else {
            nackAndRequeue(channel, deliveryTag);
        }
    }

    /**
     * 缺 eventId 的畸形消息：没有 eventId 就无法在 Inbox 登记幂等状态，
     * 无法重试也无处记账，直接进死信队列，避免在主队列里反复投递。
     */
    private void sendInvalidMessageToDead(
            SearchReconcileMessage message,
            Channel channel,
            long deliveryTag
    ) {
        if (searchReconcileProducer.sendDeadTask(message)) {
            ackQuietly(channel, deliveryTag);
        } else {
            nackAndRequeue(channel, deliveryTag);
        }
    }

    /** ack 失败只记日志不抛：此时消息将在连接恢复后重投，靠 Inbox 幂等兜底重复。 */
    private void ackQuietly(Channel channel, long deliveryTag) {
        try {
            channel.basicAck(deliveryTag, false);
        } catch (Exception e) {
            log.error("ES 校准消息 ACK 失败，deliveryTag={}", deliveryTag, e);
        }
    }

    /**
     * nack 并重回主队列（requeue=true）：用于"状态未知、不敢丢"的兜底。
     * 【坑】重回队列的消息会被立即重新消费，若失败原因是持续性的（如 DB 长时间不可用）
     * 会形成快速循环，因此业务性失败一律走 60s 重试队列而不是这里。
     */
    private void nackAndRequeue(Channel channel, long deliveryTag) {
        try {
            channel.basicNack(deliveryTag, false, true);
        } catch (Exception e) {
            log.error("ES 校准消息 NACK 失败，deliveryTag={}", deliveryTag, e);
        }
    }
}
