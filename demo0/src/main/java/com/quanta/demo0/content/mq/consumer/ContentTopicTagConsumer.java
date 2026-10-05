package com.quanta.demo0.content.mq.consumer;

import com.quanta.demo0.content.config.TopicTagMQConfig;
import com.quanta.demo0.platform.mq.enums.InboxAcquireResult;
import com.quanta.demo0.platform.mq.enums.OutboxEventType;
import com.quanta.demo0.content.mq.message.ContentTopicTagMessage;
import com.quanta.demo0.content.mq.producer.ContentTopicTagProducer;
import com.quanta.demo0.content.properties.ContentTopicProperties;
import com.quanta.demo0.content.service.ContentTopicTagService;
import com.quanta.demo0.platform.mq.service.InboxEventService;
import com.rabbitmq.client.Channel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 内容主题标签可靠消费者。
 *
 * 职责：使用 Inbox 抢占/幂等，调用标签服务并在成功后 ACK；
 * 边界：不信任消息正文，失败按重试上限转发到死信。
 *
 * ============================================================
 * 【Inbox 状态机在消费端的样子 —— 一个 switch 全展示】
 * ============================================================
 * acquire() 返回四种结果，每个分支对应一种处置：
 *   ACQUIRED        → 抢到处理权，干活（process）
 *   ALREADY_SUCCESS → 别的实例已成功过 = 幂等命中，直接 ACK 丢弃
 *   BUSY            → 别的实例正在处理（还没到 TTL），转投延迟队列稍后再来
 *                     —— **不能 requeue**：立即重投会空转打满 CPU；
 *                     也不能 ACK 丢掉：任务还没人做完
 *   DEAD            → 重试超限已判死，转投死信队列，人肉/运营介入
 *
 * 【ACK 时机是本类的灵魂】：只有 tagContent 成功 **且** Inbox markSuccess 成功，
 * 才 channel.basicAck —— 两步都完成才确认消息。
 * 反例（先 ACK 再干活）：ACK 后进程崩溃，任务永久丢失（MQ 不会再投）。
 * 这里顺序是"干活 → 记账 → ACK"，崩溃最多导致重复投递，由 Inbox 幂等兜住 ——
 * **宁可 at-least-once + 幂等，不要 at-most-once**。
 *
 * 【markSuccess 返回 false 为什么要抛异常？】
 * false = 处理权已被别的实例抢走（本实例处理太久超了 TTL）。
 * 此时任务已被别人做完，本实例的结果是多余的 —— 抛异常进失败链路，
 * 而不是覆盖别人的 SUCCESS 状态。**抢占式锁的"失去租约"必须显式处理**。
 *
 * 【毒消息 basicNack(requeue=false)】：格式坏到连 eventId 都没有的消息
 * 没法进 Inbox（没身份），只能交给 Rabbit 的 DLX 保存 —— 猜身份等于伪造幂等键。
 */
@Service
@Slf4j
@RequiredArgsConstructor
@ConditionalOnProperty(
        prefix = "quanta.recommend.topic-tags",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true)
public class ContentTopicTagConsumer {

    // Inbox 表的消费者身份：与 eventId 组成幂等键（consumer_name + event_id）。
    // 同一个事件被不同链路消费时互不干扰，全靠这个名字隔离。
    private static final String CONSUMER_NAME = "content-topic-tag-consumer";

    /**
     * 本实例身份（每个 JVM 进程随机生成），Inbox 租约（locked_by/locked_until）的"持有人"标记。
     * 【为什么随机而不是主机名/IP】实例重启后旧租约到期即可被任意实例抢占，
     * 不会出现"同名新进程误认自己是旧租约持有人"的歧义。
     */
    private final String instanceId = "content-topic-tag-" + UUID.randomUUID();
    private final InboxEventService inboxEventService;
    private final ContentTopicTagService contentTopicTagService;
    private final ContentTopicTagProducer contentTopicTagProducer;
    private final ContentTopicProperties properties;

    /** 消费主题标签请求，成功写库并标记 Inbox 后才确认 Rabbit 消息。 */
    // 【手动 ack 的五个出口，每个分支对应一种消息命运】
    //   毒消息（缺 eventId / contentId 非法 / 类型不符）→ basicNack(requeue=false) → 主队列 DLX 留档；
    //   ALREADY_SUCCESS → ACK 丢弃（幂等命中，任务早已被做完）；
    //   BUSY            → 转投延迟重试队列后 ACK（别的实例在做，本条让路）；
    //   DEAD            → 转投死信队列后 ACK（重试超限，转人工）；
    //   ACQUIRED        → process() 干活，干完才 ACK。
    // 抢占（acquire）本身抛异常 → NACK requeue，等下次投递再试。
    // 【职责边界】本方法只做"校验 + 调度 + 确认"，不写业务不开事务——
    // 打标在 ContentTopicTagService.tagContent，记账在 InboxEventService。
    @RabbitListener(queues = TopicTagMQConfig.TOPIC_TAG_QUEUE)
    public void handle(ContentTopicTagMessage message, Message mqMessage, Channel channel) {
        long deliveryTag = mqMessage.getMessageProperties().getDeliveryTag();
        if (message == null || !StringUtils.hasText(message.getEventId())
                || message.getContentId() == null || message.getContentId() <= 0
                || !OutboxEventType.CONTENT_TOPIC_TAG_REQUESTED.getCode().equals(message.getEventType())) {
            // 无法恢复的原始消息由主队列 DLX 保存，不猜事件身份、不永久 requeue。
            try { channel.basicNack(deliveryTag, false, false); }
            catch (Exception exception) { log.error("主题毒消息拒绝失败，deliveryTag={}",deliveryTag,exception); }
            return;
        }

        try {
            InboxAcquireResult acquireResult = inboxEventService.acquire(CONSUMER_NAME, instanceId, message);
            switch (acquireResult) {
                case ALREADY_SUCCESS -> ackQuietly(channel, deliveryTag);
                case BUSY -> sendBusy(message, channel, deliveryTag);
                case DEAD -> sendDead(message, channel, deliveryTag);
                case ACQUIRED -> process(message, channel, deliveryTag);
            }
        } catch (Exception exception) {
            log.error("主题标签消息抢占异常，eventId={}", message.getEventId(), exception);
            nackAndRequeue(channel, deliveryTag);
        }
    }

    /**
     * 抢占成功的执行体：干活 → 记账 → ACK，三步缺一不可。
     * 【tagContent 抛异常】走 handleFailure 分流重试/死信；
     * 【markSuccess 返回 false】= 租约已被别人抢走（本实例干活太慢超了 TTL），
     * 抛异常同样进 handleFailure —— 后续 markRetry/markDead 也会因失去所有权而失败，
     * 最终落 NACK requeue，消息重投后由 ALREADY_SUCCESS 幂等收尾，不会重复打标。
     */
    private void process(ContentTopicTagMessage message, Channel channel, long deliveryTag) {
        try {
            contentTopicTagService.tagContent(message.getContentId());
            if (!inboxEventService.markSuccess(CONSUMER_NAME, message.getEventId(), instanceId)) {
                throw new IllegalStateException("主题标签 Inbox 已失去处理权，不能标记 SUCCESS");
            }
            channel.basicAck(deliveryTag, false);
        } catch (Exception exception) {
            log.error("主题标签消息处理失败，eventId={}, contentId={}", message.getEventId(), message.getContentId(), exception);
            handleFailure(message, exception, channel, deliveryTag);
        }
    }

    /**
     * 消费失败的重试/死信分流（业务异常统一从这里过）。
     *
     * 【判定依据】消息体里的 retryCount 对比配置上限 maxRetryCount（ContentTopicProperties）：
     *   未超限 → markRetry（Inbox 记 RETRYING、租约让到 nextRetryTime）→ retryCount+1
     *            随消息转发延迟重试队列，躺 60s 后死信回主交换机再投；
     *   超限   → markDead（Inbox 记 DEAD）→ 转死信队列留档，等人工重放。
     * 【转发失败怎么办】sendRetryTask 返回 false 时 NACK requeue——消息回队列再投，
     * 而 Inbox 里已是 RETRYING 且 locked_until=nextRetryTime（未来时刻），
     * 再投会判 BUSY，由 BUSY 分支再转发一次 —— 链路自愈，任务不丢。
     * 【mark* 返回 false】说明处理权已易主，此时再转发等于替别人做主——
     * 抛异常进兜底 NACK requeue，让真正的处理方和幂等逻辑收尾。
     */
    private void handleFailure(ContentTopicTagMessage message, Exception exception, Channel channel, long deliveryTag) {
        int currentRetryCount = message.getRetryCount() == null ? 0 : message.getRetryCount();
        String error = exception.getClass().getSimpleName() + "：" + exception.getMessage();
        try {
            if (currentRetryCount >= properties.getMaxRetryCount()) {
                if (!inboxEventService.markDead(CONSUMER_NAME, message.getEventId(), instanceId, error)) {
                    throw new IllegalStateException("主题标签 Inbox 已失去处理权，不能标记 DEAD");
                }
                sendDead(message, channel, deliveryTag);
                return;
            }

            int nextRetryCount = currentRetryCount + 1;
            if (!inboxEventService.markRetry(
                    CONSUMER_NAME,
                    message.getEventId(),
                    instanceId,
                    nextRetryCount,
                    LocalDateTime.now().plusSeconds(properties.getRetryDelaySeconds()),
                    error)) {
                throw new IllegalStateException("主题标签 Inbox 已失去处理权，不能标记 RETRYING");
            }
            message.setRetryCount(nextRetryCount);
            if (contentTopicTagProducer.sendRetryTask(message)) {
                ackQuietly(channel, deliveryTag);
            } else {
                nackAndRequeue(channel, deliveryTag);
            }
        } catch (Exception failure) {
            log.error("主题标签失败状态处理异常，eventId={}", message.getEventId(), failure);
            nackAndRequeue(channel, deliveryTag);
        }
    }

    /**
     * BUSY 分支：别的实例正持有处理权（租约未到期），本实例不干活。
     * 【为什么转投而不是 requeue】立即 requeue 会被马上重新消费、继续判 BUSY，
     * 空转打满 CPU；转投 60s TTL 延迟队列等于把"再来一次"排到未来。
     */
    private void sendBusy(ContentTopicTagMessage message, Channel channel, long deliveryTag) {
        if (contentTopicTagProducer.sendRetryTask(message)) {
            ackQuietly(channel, deliveryTag);
        } else {
            nackAndRequeue(channel, deliveryTag);
        }
    }

    /**
     * DEAD 分支：Inbox 已判死（重试超限），把消息转投死信队列留档后 ACK。
     * 死信队列无消费者、无 TTL —— 队列深度 > 0 就是"有待人工处理的打标失败"的告警信号。
     */
    private void sendDead(ContentTopicTagMessage message, Channel channel, long deliveryTag) {
        if (message != null && contentTopicTagProducer.sendDeadTask(message)) {
            ackQuietly(channel, deliveryTag);
        } else {
            nackAndRequeue(channel, deliveryTag);
        }
    }

    /**
     * 【为什么吞异常】ACK 失败通常意味着 channel 已关闭/连接已断，
     * 消息的最终命运由 broker 的连接恢复策略决定，本地重试 ACK 没有意义，记录即可。
     */
    private void ackQuietly(Channel channel, long deliveryTag) {
        try {
            channel.basicAck(deliveryTag, false);
        } catch (Exception exception) {
            log.error("主题标签消息 ACK 失败，deliveryTag={}", deliveryTag, exception);
        }
    }

    /**
     * 退回消息并要求重投（requeue=true）——一切"暂时做不了/不确定"分支的兜底。
     * 与毒消息的 nack(requeue=false) 相反：这里承认问题可能是暂时的，交还 broker 再来一次。
     */
    private void nackAndRequeue(Channel channel, long deliveryTag) {
        try {
            channel.basicNack(deliveryTag, false, true);
        } catch (Exception exception) {
            log.error("主题标签消息 NACK 失败，deliveryTag={}", deliveryTag, exception);
        }
    }
}
