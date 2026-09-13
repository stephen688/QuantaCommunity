// d:/download/资料/day01/后端初始工程/demo0/src/main/java/com/quanta/demo0/mq/producer/ModerationProducer.java
package com.quanta.demo0.mq.producer;

import com.quanta.demo0.config.RabbitMQConfig;
import com.quanta.demo0.mq.message.ModerationTaskMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 审核任务生产者
 * 用于发送审核任务到 RabbitMQ 队列
 */
@Slf4j
@Component
public class ModerationProducer {

    @Autowired
    private ReliableRabbitPublisher reliableRabbitPublisher;

    public boolean sendRetryTask(
            ModerationTaskMessage message
    ) {
        try {
            if (!reliableRabbitPublisher.send(
                    RabbitMQConfig.MODERATION_RETRY_EXCHANGE,
                    RabbitMQConfig.MODERATION_RETRY_ROUTING_KEY,
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
