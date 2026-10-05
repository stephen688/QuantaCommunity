package com.quanta.demo0.moderation.mq.consumer;

import com.quanta.demo0.moderation.config.ModerationMQConfig;
import com.quanta.demo0.moderation.mq.message.ModerationTaskMessage;
import com.quanta.demo0.moderation.result.ModerationWorkflowResult;
import com.quanta.demo0.moderation.service.ModerationWorkflowService;
import com.rabbitmq.client.Channel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

/**
 * 审核任务消息监听器。
 *
 * <p>业务审核、Inbox 租约和重试编排全部由工作流服务负责；监听器只负责接收消息，
 * 并依据工作流结果执行 ACK/NACK。</p>
 *
 * ============================================================
 * 【为什么监听器要手动 ack，还把动作交给工作流结果决定？】
 * ============================================================
 * application.yml 配了 acknowledge-mode: manual（prefetch: 1），消息在显式
 * basicAck 之前一直"未确认"——消费中途宕机，RabbitMQ 会把消息重投给别人，
 * **任务不会丢**。监听器自己不猜"该 ack 还是 nack"：
 * 工作流返回 ACK（含"已转投重试队列"）→ basicAck；
 * 返回 REQUEUE → nack + requeue=true（立即重投）；
 * 返回 DEAD → nack + requeue=false（经主队列的 DLX 进死信队列，
 * 见 ModerationMQConfig）。
 */
@Slf4j
@Component
public class ModerationConsumer {

    @Autowired
    private ModerationWorkflowService workflowService;

    /**
     * 审核消息入口。
     * 【坑】这里连工作流抛异常都要兜住并降级为 REQUEUE——监听器方法一旦
     * 向上抛异常，container 的行为与手动 ack 模式组合起来不好推理，
     * 所以统一"先转成结果，再统一 ack/nack"。
     */
    @RabbitListener(
            queues = ModerationMQConfig.MODERATION_QUEUE
    )
    public void handleModerationTask(
            ModerationTaskMessage task,
            Channel channel,
            @Header(AmqpHeaders.DELIVERY_TAG)
            long deliveryTag
    ) {
        ModerationWorkflowResult workflowResult;

        try {
            workflowResult = workflowService.process(task);
        } catch (Exception exception) {
            log.error("审核工作流执行异常，deliveryTag={}", deliveryTag, exception);
            workflowResult = ModerationWorkflowResult.REQUEUE;
        }

        acknowledgeMessage(workflowResult, channel, deliveryTag);
    }

    /**
     * 把工作流结论翻译成 channel 动作：ACK → 确认；REQUEUE → nack 立即重投；
     * DEAD → nack 不重投（走主队列的 x-dead-letter-exchange 进死信队列）。
     *
     * 【坑】ack/nack 本身也可能失败（channel 已断、连接抖动）。这里再兜一层：
     * 先试 nack(requeue=true) 把消息还给队列，再失败就只能留日志——
     * 连接恢复后 RabbitMQ 会因消息一直未 ack 而重新投递，不会丢消息。
     */
    private void acknowledgeMessage(
            ModerationWorkflowResult workflowResult,
            Channel channel,
            long deliveryTag
    ) {
        try {
            if (workflowResult == ModerationWorkflowResult.ACK) {
                channel.basicAck(deliveryTag, false);
                return;
            }

            channel.basicNack(
                    deliveryTag,
                    false,
                    workflowResult == ModerationWorkflowResult.REQUEUE
            );
        } catch (Exception exception) {
            log.error("审核消息 ACK/NACK 失败，deliveryTag={}, result={}",
                    deliveryTag, workflowResult, exception);
            try {
                channel.basicNack(deliveryTag, false, true);
            } catch (Exception retryException) {
                log.error("审核消息失败后的重试 NACK 也失败，deliveryTag={}",
                        deliveryTag, retryException);
            }
        }
    }
}
