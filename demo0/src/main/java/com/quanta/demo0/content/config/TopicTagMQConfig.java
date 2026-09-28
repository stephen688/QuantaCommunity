package com.quanta.demo0.content.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 内容主题标签 RabbitMQ 拓扑。
 *
 * 职责：声明主题标签主队列、延迟重试队列和死信队列；
 * 边界：首发事件由 OutboxDispatcher 负责，本配置不发送消息。
 */
@Configuration
public class TopicTagMQConfig {

    public static final String TOPIC_TAG_QUEUE = "content.topic.tag.queue";
    public static final String TOPIC_TAG_EXCHANGE = "content.topic.tag.exchange";
    public static final String TOPIC_TAG_ROUTING_KEY = "content.topic.tag";

    public static final String TOPIC_TAG_RETRY_QUEUE = "content.topic.tag.retry.queue";
    public static final String TOPIC_TAG_RETRY_EXCHANGE = "content.topic.tag.retry.exchange";
    public static final String TOPIC_TAG_RETRY_ROUTING_KEY = "content.topic.tag.retry";

    public static final String TOPIC_TAG_DLX_QUEUE = "content.topic.tag.dlx.queue";
    public static final String TOPIC_TAG_DLX_EXCHANGE = "content.topic.tag.dlx.exchange";
    public static final String TOPIC_TAG_DLX_ROUTING_KEY = "content.topic.tag.dlx";

    @Bean
    public Queue topicTagQueue() {
        return QueueBuilder.durable(TOPIC_TAG_QUEUE)
                .withArgument("x-dead-letter-exchange", TOPIC_TAG_DLX_EXCHANGE)
                .withArgument("x-dead-letter-routing-key", TOPIC_TAG_DLX_ROUTING_KEY).build();
    }

    @Bean
    public Queue topicTagRetryQueue() {
        return QueueBuilder.durable(TOPIC_TAG_RETRY_QUEUE)
                .withArgument("x-message-ttl", 60000)
                .withArgument("x-dead-letter-exchange", TOPIC_TAG_EXCHANGE)
                .withArgument("x-dead-letter-routing-key", TOPIC_TAG_ROUTING_KEY)
                .build();
    }

    @Bean
    public Queue topicTagDlxQueue() {
        return QueueBuilder.durable(TOPIC_TAG_DLX_QUEUE).build();
    }

    @Bean
    public DirectExchange topicTagExchange() {
        return new DirectExchange(TOPIC_TAG_EXCHANGE);
    }

    @Bean
    public DirectExchange topicTagRetryExchange() {
        return new DirectExchange(TOPIC_TAG_RETRY_EXCHANGE);
    }

    @Bean
    public DirectExchange topicTagDlxExchange() {
        return new DirectExchange(TOPIC_TAG_DLX_EXCHANGE);
    }

    @Bean
    public Binding topicTagBinding() {
        return BindingBuilder.bind(topicTagQueue())
                .to(topicTagExchange())
                .with(TOPIC_TAG_ROUTING_KEY);
    }

    @Bean
    public Binding topicTagRetryBinding() {
        return BindingBuilder.bind(topicTagRetryQueue())
                .to(topicTagRetryExchange())
                .with(TOPIC_TAG_RETRY_ROUTING_KEY);
    }

    @Bean
    public Binding topicTagDlxBinding() {
        return BindingBuilder.bind(topicTagDlxQueue())
                .to(topicTagDlxExchange())
                .with(TOPIC_TAG_DLX_ROUTING_KEY);
    }
}
