package com.quanta.demo0.search.mq.producer;

import com.quanta.demo0.platform.mq.producer.ReliableRabbitPublisher;
import com.quanta.demo0.search.config.SearchMQConfig;
import com.quanta.demo0.search.mq.message.SearchReconcileMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * ES 校准消费者辅助生产者。
 *
 * 注意：
 * 第一次发送由 OutboxDispatcher 负责；
 * 这个 Producer 只负责消费者失败后的重试和死信。
 *
 * ============================================================
 * 【为什么重试/死信不走 Outbox，而是直接投递？】
 * ============================================================
 * Outbox 解决的是"业务事务与发消息的原子性"：事件必须与业务写库
 * 同事务落库，防止业务成功而消息丢失。而重试/死信消息产生于消费侧，
 * 业务事务早已结束，不存在原子性问题；此刻它们是 MQ 内部的流转衔接
 * （消费失败 → 重试队列/死信队列），直接经 ReliableRabbitPublisher
 * 带 publisher confirm 投递即可——**confirm + 未路由检查都通过才返回
 * true，返回 false 时消费侧会 nack 重回主队列，消息不会丢**。
 * 若再绕 Outbox 反而引入第二轮调度延迟，还可能与 Inbox 状态脱节。
 */
@Component
@Slf4j
public class SearchReconcileProducer {

    @Autowired
    private ReliableRabbitPublisher reliableRabbitPublisher;

    /**
     * 发送 ES 校准重试消息。
     * 投递目标：重试交换机 → 重试队列（停留 60s）→ 死信回主队列。
     * 返回 false（confirm 超时 / broker NACK / 未路由）时消费侧会 nack 重回主队列。
     */
    public boolean sendRetryTask(SearchReconcileMessage message) {
        try {
            if (!reliableRabbitPublisher.send(
                    SearchMQConfig.SEARCH_RECONCILE_RETRY_EXCHANGE,
                    SearchMQConfig.SEARCH_RECONCILE_RETRY_ROUTING_KEY,
                    message,
                    message.getEventId())) {
                return false;
            }

            log.info("发送 ES 校准重试消息成功，eventId={}, retryCount={}", message.getEventId(), message.getRetryCount());
            return true;
        } catch (Exception e) {
            log.error("发送 ES 校准重试消息失败，eventId={}", message.getEventId(), e);
            return false;
        }
    }

    /**
     * 将超过重试次数的消息发送到死信队列。
     * 这是消息生命周期的终点：进死信队列留档，等人工排查后手工补偿，
     * 不再自动重试。返回 false 时消费侧会 nack 重回主队列，消息不丢。
     */
    public boolean sendDeadTask(SearchReconcileMessage message) {
        try {
            if (!reliableRabbitPublisher.send(
                    SearchMQConfig.SEARCH_RECONCILE_DLX_EXCHANGE,
                    SearchMQConfig.SEARCH_RECONCILE_DLX_ROUTING_KEY,
                    message,
                    message.getEventId())) {
                return false;
            }

            log.error("ES 校准消息进入死信队列，eventId={}", message.getEventId());
            return true;
        } catch (Exception e) {
            log.error("发送 ES 校准死信消息失败，eventId={}", message.getEventId(), e);
            return false;
        }
    }
}
