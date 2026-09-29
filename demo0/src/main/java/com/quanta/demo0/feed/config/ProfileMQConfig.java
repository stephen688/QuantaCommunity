package com.quanta.demo0.feed.config;

import org.springframework.amqp.core.*;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 显式偏好可靠拓扑：持久化主队列、60秒重试及死信；不改既有行为队列参数。 */
@Configuration
public class ProfileMQConfig {
    public static final String PROFILE_QUEUE = "user.profile.updated.queue";
    public static final String PROFILE_EXCHANGE = "user.profile.updated.exchange";
    public static final String PROFILE_ROUTING_KEY = "user.profile.updated";
    public static final String PROFILE_RETRY_QUEUE = "user.profile.updated.retry.queue";
    public static final String PROFILE_RETRY_EXCHANGE = "user.profile.updated.retry.exchange";
    public static final String PROFILE_RETRY_ROUTING_KEY = "user.profile.updated.retry";
    public static final String PROFILE_DLX_QUEUE = "user.profile.updated.dlx.queue";
    public static final String PROFILE_DLX_EXCHANGE = "user.profile.updated.dlx.exchange";
    public static final String PROFILE_DLX_ROUTING_KEY = "user.profile.updated.dlx";

    /** 统一声明当前专用拓扑，首次业务发送仍由 Outbox Dispatcher 承担。 */
    @Bean
    public Declarables profileTopology() {
        Queue main = QueueBuilder.durable(PROFILE_QUEUE)
                .withArgument("x-dead-letter-exchange", PROFILE_DLX_EXCHANGE)
                .withArgument("x-dead-letter-routing-key", PROFILE_DLX_ROUTING_KEY).build();
        Queue retry = QueueBuilder.durable(PROFILE_RETRY_QUEUE).withArgument("x-message-ttl", 60000)
                .withArgument("x-dead-letter-exchange", PROFILE_EXCHANGE)
                .withArgument("x-dead-letter-routing-key", PROFILE_ROUTING_KEY).build();
        Queue dead = QueueBuilder.durable(PROFILE_DLX_QUEUE).build();
        DirectExchange exchange = new DirectExchange(PROFILE_EXCHANGE);
        DirectExchange retryExchange = new DirectExchange(PROFILE_RETRY_EXCHANGE);
        DirectExchange deadExchange = new DirectExchange(PROFILE_DLX_EXCHANGE);
        return new Declarables(main, retry, dead, exchange, retryExchange, deadExchange,
                BindingBuilder.bind(main).to(exchange).with(PROFILE_ROUTING_KEY),
                BindingBuilder.bind(retry).to(retryExchange).with(PROFILE_RETRY_ROUTING_KEY),
                BindingBuilder.bind(dead).to(deadExchange).with(PROFILE_DLX_ROUTING_KEY));
    }
}
