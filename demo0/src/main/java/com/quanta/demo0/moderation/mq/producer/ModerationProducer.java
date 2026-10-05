// d:/download/资料/day01/后端初始工程/demo0/src/main/java/com/quanta/demo0/mq/producer/ModerationProducer.java
package com.quanta.demo0.moderation.mq.producer;

import com.quanta.demo0.platform.mq.producer.ReliableRabbitPublisher;
import com.quanta.demo0.moderation.config.ModerationMQConfig;
import com.quanta.demo0.moderation.mq.message.ModerationTaskMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 审核任务生产者
 * 用于发送审核任务到 RabbitMQ 队列
 *
 * ============================================================
 * 【这个生产者只管"重试"，首发走的是 Outbox】
 * ============================================================
 * 首次投递：业务侧写库时同时写 Outbox 事件（MODERATION_REQUESTED），由
 * platform/mq 的 Outbox 中继按 OutboxRouteRegistry 注册的路由发往
 * moderation.exchange——**"业务落库"和"消息可投"是同一个本地事务**，不经过本类。
 * 本类只服务消费端：工作流失败重试、BUSY 让位时，把消息发往
 * moderation.retry.exchange（在 retry 队列躺 60 秒后死信回主队列，见 ModerationMQConfig）。
 *
 * 【为什么返回 boolean 而不是抛异常？】可靠性由 ReliableRabbitPublisher 保证
 * （publisher-confirm + 路由失败检查，超时 5 秒），发送失败不炸消费线程，
 * **由调用方（ModerationWorkflowServiceImpl）决定降级**：改走 nack requeue。
 */
@Slf4j
@Component
public class ModerationProducer {

    @Autowired
    private ReliableRabbitPublisher reliableRabbitPublisher;

    /**
     * 发送一条审核重试任务到延迟重试队列。
     *
     * 【关键】以 message.getEventId() 作为关联 ID 参与可靠投递确认，
     * 保证"broker 真正收到且成功路由"才算发送成功（返回 true）。
     *
     * @return true = 已确认投递；false = NACK/未路由/超时/异常，调用方需降级处理
     */
    public boolean sendRetryTask(
            ModerationTaskMessage message
    ) {
        try {
            if (!reliableRabbitPublisher.send(
                    ModerationMQConfig.MODERATION_RETRY_EXCHANGE,
                    ModerationMQConfig.MODERATION_RETRY_ROUTING_KEY,
                    message,
                    message.getEventId())) {
                return false;
            }

            log.info(
                    "发送审核重试任务，targetId={}, retryCount={}",
                    message.getTargetId(),
                    message.getRetryCount()
            );

            return true;

        } catch (Exception e) {
            log.error(
                    "发送审核重试任务失败，targetId={}",
                    message.getTargetId(),
                    e
            );

            return false;
        }
    }
}
