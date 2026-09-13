package com.quanta.demo0.mq.producer;

import com.quanta.demo0.config.RabbitMQConfig;
import com.quanta.demo0.mq.message.FeedDeleteMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
@Slf4j
public class FeedDeleteProducer {

    @Autowired
        private ReliableRabbitPublisher reliableRabbitPublisher;

        /**
         * 发送 Feed 删除消费重试消息，首次发送统一由 OutboxDispatcher 负责。
         */
        public boolean sendRetryTask(FeedDeleteMessage message) {
            try {
                if (!reliableRabbitPublisher.send(
                        RabbitMQConfig.FEED_DELETE_RETRY_EXCHANGE,
                        RabbitMQConfig.FEED_DELETE_RETRY_ROUTING_KEY,
                        message,
                        message.getEventId())) {
                    return false;
                }
                log.info("发送 Feed 删除重试消息成功，eventId={}, retryCount={}", message.getEventId(), message.getRetryCount());
                return true;
            } catch (Exception e) {
                log.error("发送 Feed 删除重试消息失败，eventId={}", message.getEventId(), e);
                return false;
            }
        }

        public boolean sendDeadTask(FeedDeleteMessage message) {
            try {
                if (!reliableRabbitPublisher.send(
                        RabbitMQConfig.FEED_DELETE_DLX_EXCHANGE,
                        RabbitMQConfig.FEED_DELETE_DLX_ROUTING_KEY,
                        message,
                        message.getEventId())) {
                    return false;
                }
                log.error("Feed 删除消息进入死信队列，eventId={}", message.getEventId());
                return true;
            } catch (Exception e) {
                log.error("发送 Feed 删除死信消息失败，eventId={}", message.getEventId(), e);
                return false;
            }
        }
}
