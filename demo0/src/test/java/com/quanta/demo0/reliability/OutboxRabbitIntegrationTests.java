package com.quanta.demo0.reliability;

import com.quanta.demo0.entity.OutboxEvent;
import com.quanta.demo0.mq.message.OutboxRoute;
import com.quanta.demo0.mq.outbox.OutboxDispatcher;
import com.quanta.demo0.mq.outbox.OutboxRouteRegistry;
import com.quanta.demo0.properties.OutboxDispatchProperties;
import com.quanta.demo0.service.OutboxEventService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory.ConfirmType;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.test.util.ReflectionTestUtils;
import org.testcontainers.containers.RabbitMQContainer;

import java.time.LocalDateTime;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class OutboxRabbitIntegrationTests {

    private static final String EXCHANGE = "reliability.integration.exchange";
    private static final String QUEUE = "reliability.integration.queue";
    private static final String ROUTING_KEY = "reliability.integration.route";

    private static final RabbitMQContainer RABBITMQ =
            new RabbitMQContainer("rabbitmq:3.13-management-alpine");

    private static CachingConnectionFactory connectionFactory;
    private static RabbitTemplate rabbitTemplate;
    private static RabbitAdmin rabbitAdmin;

    @BeforeAll
    static void startRabbitMq() {
        RABBITMQ.start();
        createClient();
        declareTopology();
    }

    @AfterAll
    static void stopRabbitMq() {
        if (connectionFactory != null) {
            connectionFactory.destroy();
        }
        RABBITMQ.stop();
    }

    @Test
    @Order(1)
    void confirmAckWithoutReturnMarksOutboxSent() {
        OutboxEventService service = mock(OutboxEventService.class);
        OutboxRouteRegistry registry = mock(OutboxRouteRegistry.class);
        OutboxEvent event = testEvent();

        when(service.claimBatch(anyString())).thenReturn(List.of(event));
        when(service.markSent(eq(event.getId()), anyString())).thenReturn(true);
        when(registry.resolve(event)).thenReturn(route(ROUTING_KEY));

        dispatcher(service, registry).dispatch();

        verify(service).markSent(eq(event.getId()), anyString());
        verify(service, never()).markRetry(anyLong(), anyString(), anyInt(), any(), anyString());
    }

    @Test
    @Order(2)
    void confirmAckWithReturnDoesNotMarkOutboxSent() {
        OutboxEventService service = mock(OutboxEventService.class);
        OutboxRouteRegistry registry = mock(OutboxRouteRegistry.class);
        OutboxEvent event = testEvent();

        when(service.claimBatch(anyString())).thenReturn(List.of(event));
        when(service.markRetry(anyLong(), anyString(), anyInt(), any(), anyString())).thenReturn(true);
        when(registry.resolve(event)).thenReturn(route("route.without.queue"));

        dispatcher(service, registry).dispatch();

        verify(service, never()).markSent(anyLong(), anyString());
        verify(service).markRetry(
                eq(event.getId()),
                anyString(),
                eq(1),
                any(),
                contains("未路由")
        );
    }

    @Test
    @Order(3)
    void exhaustedOutboxRetriesEnterDead() {
        OutboxEventService service = mock(OutboxEventService.class);
        OutboxRouteRegistry registry = mock(OutboxRouteRegistry.class);
        OutboxEvent event = testEvent();
        event.setRetryCount(6);

        when(service.claimBatch(anyString())).thenReturn(List.of(event));
        when(service.markDead(anyLong(), anyString(), anyString())).thenReturn(true);
        when(registry.resolve(event)).thenReturn(route("route.without.queue"));

        dispatcher(service, registry).dispatch();

        verify(service).markDead(eq(event.getId()), anyString(), contains("未路由"));
        verify(service, never()).markSent(anyLong(), anyString());
        verify(service, never()).markRetry(anyLong(), anyString(), anyInt(), any(), anyString());
    }

    @Test
    @Order(4)
    void rabbitMqOutageRetriesAndRecoverySendsAutomatically() throws Exception {
        OutboxEventService service = mock(OutboxEventService.class);
        OutboxRouteRegistry registry = mock(OutboxRouteRegistry.class);
        OutboxEvent event = testEvent();

        when(service.claimBatch(anyString())).thenReturn(List.of(event));
        when(service.markRetry(anyLong(), anyString(), anyInt(), any(), anyString())).thenReturn(true);
        when(service.markSent(eq(event.getId()), anyString())).thenReturn(true);
        when(registry.resolve(event)).thenReturn(route(ROUTING_KEY));

        OutboxDispatcher dispatcher = dispatcher(service, registry);
        String containerId = RABBITMQ.getContainerId();
        RABBITMQ.getDockerClient().pauseContainerCmd(containerId).exec();
        connectionFactory.resetConnection();

        dispatcher.dispatch();

        verify(service).markRetry(eq(event.getId()), anyString(), eq(1), any(), anyString());

        RABBITMQ.getDockerClient().unpauseContainerCmd(containerId).exec();
        waitUntilRabbitMqReady();
        connectionFactory.resetConnection();
        declareTopology();

        event.setRetryCount(1);
        dispatcher.dispatch();

        verify(service).markSent(eq(event.getId()), anyString());
    }

    private static OutboxDispatcher dispatcher(
            OutboxEventService service,
            OutboxRouteRegistry registry
    ) {
        OutboxDispatchProperties properties = new OutboxDispatchProperties();
        properties.setConfirmTimeoutSeconds(2);
        properties.setRetryDelaysSeconds(List.of(1L, 1L, 1L, 1L, 1L, 1L));

        OutboxDispatcher dispatcher = new OutboxDispatcher();
        ReflectionTestUtils.setField(dispatcher, "rabbitTemplate", rabbitTemplate);
        ReflectionTestUtils.setField(dispatcher, "outboxEventService", service);
        ReflectionTestUtils.setField(dispatcher, "outboxRouteRegistry", registry);
        ReflectionTestUtils.setField(dispatcher, "outboxDispatchProperties", properties);
        return dispatcher;
    }

    private static OutboxRoute route(String routingKey) {
        return OutboxRoute.builder()
                .exchange(EXCHANGE)
                .routingKey(routingKey)
                .message(Map.of("eventId", UUID.randomUUID().toString()))
                .build();
    }

    private static OutboxEvent testEvent() {
        return OutboxEvent.builder()
                .id(1L)
                .eventId(UUID.randomUUID().toString())
                .eventType("NOTIFICATION_REQUESTED")
                .aggregateType("CONTENT")
                .aggregateId(10L)
                .payload("{}")
                .status("PROCESSING")
                .retryCount(0)
                .nextRetryTime(LocalDateTime.now())
                .build();
    }

    private static void createClient() {
        connectionFactory = new CachingConnectionFactory(
                RABBITMQ.getHost(),
                RABBITMQ.getAmqpPort()
        );
        connectionFactory.setUsername(RABBITMQ.getAdminUsername());
        connectionFactory.setPassword(RABBITMQ.getAdminPassword());
        connectionFactory.setPublisherConfirmType(ConfirmType.CORRELATED);
        connectionFactory.setPublisherReturns(true);
        connectionFactory.getRabbitConnectionFactory().setConnectionTimeout(1_000);

        rabbitTemplate = new RabbitTemplate(connectionFactory);
        rabbitTemplate.setMandatory(true);
        rabbitTemplate.setMessageConverter(new Jackson2JsonMessageConverter());
        rabbitAdmin = new RabbitAdmin(connectionFactory);
    }

    private static void declareTopology() {
        DirectExchange exchange = new DirectExchange(EXCHANGE, true, false);
        Queue queue = new Queue(QUEUE, true, false, false);
        rabbitAdmin.declareExchange(exchange);
        rabbitAdmin.declareQueue(queue);
        rabbitAdmin.declareBinding(BindingBuilder.bind(queue).to(exchange).with(ROUTING_KEY));
    }

    private static void waitUntilRabbitMqReady() throws Exception {
        Exception lastFailure = null;

        for (int attempt = 0; attempt < 30; attempt++) {
            try (Socket socket = new Socket()) {
                socket.connect(
                        new InetSocketAddress(RABBITMQ.getHost(), RABBITMQ.getAmqpPort()),
                        1_000
                );
                if (socket.isConnected()) {
                    return;
                }
            } catch (Exception exception) {
                lastFailure = exception;
            }
            Thread.sleep(1_000);
        }

        throw new IllegalStateException("RabbitMQ 重启后未恢复", lastFailure);
    }
}
