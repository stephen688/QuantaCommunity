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
 */
@Slf4j
@Component
public class ModerationConsumer {

    @Autowired
    private ModerationWorkflowService workflowService;

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
