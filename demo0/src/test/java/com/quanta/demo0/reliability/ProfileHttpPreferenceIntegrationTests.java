package com.quanta.demo0.reliability;
import com.quanta.demo0.platform.mq.properties.OutboxDispatchProperties;
import com.quanta.demo0.platform.redis.properties.ReadPathCacheProperties;
import com.quanta.demo0.platform.security.properties.JwtProperties;
import com.quanta.demo0.platform.security.properties.QuantabotProperties;
import com.quanta.demo0.platform.security.properties.SecurityProperties;


import com.fasterxml.jackson.databind.ObjectMapper;
import com.quanta.demo0.config.ProfileMQConfig;
import com.quanta.demo0.constant.JwtClaimsConstant;
import com.quanta.demo0.constant.RedisConstants;
import com.quanta.demo0.controller.bot.BotProfileController;
import com.quanta.demo0.dto.BotProfileEventDTO;
import com.quanta.demo0.content.entity.Content;
import com.quanta.demo0.mapper.ContentMapper;
import com.quanta.demo0.mq.consumer.ProfileReconcileConsumer;
import com.quanta.demo0.mq.message.ProfileReconcileMessage;
import com.quanta.demo0.mq.outbox.OutboxDispatcher;
import com.quanta.demo0.mq.outbox.OutboxRouteRegistry;
import com.quanta.demo0.mq.producer.ProfileReconcileProducer;
import com.quanta.demo0.mq.producer.ReliableRabbitPublisher;
import com.quanta.demo0.properties.*;
import com.quanta.demo0.security.*;
import com.quanta.demo0.service.ExplicitPreferenceService;
import com.quanta.demo0.service.InboxEventService;
import com.quanta.demo0.service.OutboxEventService;
import com.quanta.demo0.service.Impl.ExplicitPreferenceServiceImpl;
import com.quanta.demo0.service.Impl.InboxEventServiceImpl;
import com.quanta.demo0.service.Impl.OutboxEventServiceImpl;
import com.quanta.demo0.service.Impl.RecommendRerankServiceImpl;
import com.quanta.demo0.service.Impl.UserProfileServiceImpl;
import com.quanta.demo0.utils.JwtUtil;
import com.rabbitmq.client.GetResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.context.web.WebAppConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultHandlers.print;

/**
 * 一条有界的真实边界证明：HTTP service-token 鉴权 → MySQL 事实/Outbox → RabbitMQ → Inbox/Redis → 推荐排序。
 * 不启动生产主服务，也不调用 LLM；HTTP 写入后的同一 eventId MQ 重投只允许一个画像结果。
 */
@Testcontainers
@WebAppConfiguration
@SpringJUnitConfig(ProfileHttpPreferenceIntegrationTests.TestConfig.class)
@TestPropertySource(properties = {
        "quanta.jwt.user-secret-key=01234567890123456789012345678901",
        "quanta.jwt.user-token-name=authorization",
        "quantabot.bot-user-id=10000",
        "quanta.recommend.warmup-on-startup=false",
        "spring.rabbitmq.listener.simple.auto-startup=false",
        "spring.ai.openai.api-key=test-only-no-network",
        "spring.ai.qwen-openai.api-key=test-only-no-network",
        "mybatis.configuration.map-underscore-to-camel-case=true",
        "mybatis.type-aliases-package=com.quanta.demo0.entity"
})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ProfileHttpPreferenceIntegrationTests {

    /** Reuse the isolated containers and schema fixture used by the adjacent transport proof. */
    @Container
    static final MySQLContainer<?> MYSQL = ProfilePreferenceIntegrationTests.MYSQL;
    @Container
    static final GenericContainer<?> REDIS = ProfilePreferenceIntegrationTests.REDIS;
    @Container
    static final RabbitMQContainer RABBIT = ProfilePreferenceIntegrationTests.RABBIT;

    @DynamicPropertySource
    static void dataSource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.datasource.driver-class-name", MYSQL::getDriverClassName);
        registry.add("mybatis.mapper-locations", () -> "classpath:mapper/*.xml");
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    }

    @Autowired org.springframework.web.context.WebApplicationContext applicationContext;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired StringRedisTemplate redis;
    @Autowired ExplicitPreferenceService preferences;
    @Autowired OutboxEventService outbox;
    @Autowired InboxEventService inbox;
    @Autowired ContentMapper contentMapper;

    private CachingConnectionFactory rabbitConnection;
    private RabbitTemplate rabbit;
    private OutboxDispatcher dispatcher;
    private MockMvc mockMvc;

    @BeforeEach
    void prepare() throws Exception {
        mockMvc = org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup(applicationContext)
                .apply(org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity())
                .build();
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS tb_user_auth (
                    auth_id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
                    user_id BIGINT NOT NULL,
                    audit_status INT NOT NULL DEFAULT 0
                ) ENGINE=InnoDB
                """);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS user_role (
                    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
                    user_id BIGINT NOT NULL,
                    role_code VARCHAR(64) NOT NULL,
                    created_by BIGINT DEFAULT NULL,
                    UNIQUE KEY uk_test_user_role (user_id, role_code)
                ) ENGINE=InnoDB
                """);
        try (var connection = jdbc.getDataSource().getConnection()) {
            var migration = new org.springframework.core.io.ClassPathResource("db/V_recommend_topics_profile.sql");
            org.springframework.jdbc.datasource.init.ScriptUtils.executeSqlScript(connection, migration);
            org.springframework.jdbc.datasource.init.ScriptUtils.executeSqlScript(connection, migration);
        }

        jdbc.update("DELETE FROM tb_inbox_event");
        jdbc.update("DELETE FROM tb_outbox_event");
        jdbc.update("DELETE FROM tb_user_profile_signal");
        jdbc.update("DELETE FROM user_role");
        jdbc.update("DELETE FROM tb_user_auth");
        jdbc.update("DELETE FROM tb_user");
        jdbc.update("DELETE FROM tb_content");
        jdbc.update("INSERT INTO tb_user(id,nick_name,account_status,is_deleted) VALUES(10000,'bot',0,0),(123,'target',0,0)");
        jdbc.update("INSERT INTO user_role(user_id,role_code,created_by) VALUES(10000,'BOT',10000)");
        redis.delete(List.of(
                RedisConstants.USER_PROFILE_EXPLICIT_KEY + "123",
                RedisConstants.RECOMMEND_EXPOSED_KEY_PREFIX + "123",
                RedisConstants.RECOMMEND_ALL_KEY,
                RedisConstants.RECOMMEND_HOT_ALL_KEY
        ));

        rabbitConnection = new CachingConnectionFactory(RABBIT.getHost(), RABBIT.getAmqpPort());
        rabbitConnection.setUsername(RABBIT.getAdminUsername());
        rabbitConnection.setPassword(RABBIT.getAdminPassword());
        rabbitConnection.setPublisherConfirmType(CachingConnectionFactory.ConfirmType.CORRELATED);
        rabbitConnection.setPublisherReturns(true);
        RabbitAdmin admin = new RabbitAdmin(rabbitConnection);
        for (var declaration : new ProfileMQConfig().profileTopology().getDeclarables()) {
            if (declaration instanceof org.springframework.amqp.core.Queue queue) {
                admin.declareQueue(queue);
            } else if (declaration instanceof org.springframework.amqp.core.Exchange exchange) {
                admin.declareExchange(exchange);
            } else if (declaration instanceof org.springframework.amqp.core.Binding binding) {
                admin.declareBinding(binding);
            }
        }
        admin.purgeQueue(ProfileMQConfig.PROFILE_QUEUE);
        admin.purgeQueue(ProfileMQConfig.PROFILE_RETRY_QUEUE);
        admin.purgeQueue(ProfileMQConfig.PROFILE_DLX_QUEUE);
        rabbit = new RabbitTemplate(rabbitConnection);
        rabbit.setMandatory(true);
        rabbit.setMessageConverter(new Jackson2JsonMessageConverter(json));

        dispatcher = new OutboxDispatcher();
        ReflectionTestUtils.setField(dispatcher, "rabbitTemplate", rabbit);
        ReflectionTestUtils.setField(dispatcher, "outboxEventService", outbox);
        ReflectionTestUtils.setField(dispatcher, "outboxRouteRegistry", new OutboxRouteRegistry(json));
        ReflectionTestUtils.setField(dispatcher, "outboxDispatchProperties", new OutboxDispatchProperties());
    }

    @AfterEach
    void close() {
        if (rabbitConnection != null) {
            rabbitConnection.destroy();
        }
    }

    @Test
    void httpServiceTokenAndDuplicateEventReachCurrentProfileAndRanking() throws Exception {
        BotProfileEventDTO event = event("negative");
        String token = serviceToken();

        postEvent(token, event).andExpect(jsonPath("$.data").value(true));
        postEvent(token, event).andExpect(jsonPath("$.data").value(false));

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM tb_user_profile_signal WHERE event_id=?", Integer.class, event.getEventId()))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM tb_outbox_event WHERE event_id=?", Integer.class, event.getEventId()))
                .isEqualTo(1);

        // DATETIME(3) 会舍入入库的微秒值；按实际调度语义等任务到期，不能把一次轮询当永久失败。
        org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(3))
                .pollInterval(java.time.Duration.ofMillis(50)).untilAsserted(() -> {
                    dispatcher.dispatch();
                    assertThat(jdbc.queryForObject("SELECT status FROM tb_outbox_event WHERE event_id=?",String.class,event.getEventId()))
                            .isEqualTo("SENT");
                });

        try (var connection = rabbitConnection.createConnection(); var channel = connection.createChannel(false)) {
            GetResponse delivery = channel.basicGet(ProfileMQConfig.PROFILE_QUEUE, false);
            assertThat(delivery).isNotNull();
            ProfileReconcileMessage message = json.readValue(delivery.getBody(), ProfileReconcileMessage.class);
            ProfileReconcileConsumer consumer = new ProfileReconcileConsumer(
                    inbox,
                    preferences,
                    new ProfileReconcileProducer(new ReliableRabbitPublisher(rabbit, new OutboxDispatchProperties()))
            );
            consumer.handle(message, message(delivery), channel);

            assertThat(jdbc.queryForObject("SELECT status FROM tb_inbox_event WHERE event_id=?", String.class, event.getEventId()))
                    .isEqualTo("SUCCESS");
            assertThat(redis.opsForHash().get(RedisConstants.USER_PROFILE_EXPLICIT_KEY + "123", "basketball"))
                    .isEqualTo("-1.25");

            channel.basicPublish(ProfileMQConfig.PROFILE_EXCHANGE, ProfileMQConfig.PROFILE_ROUTING_KEY,
                    null, delivery.getBody());
            GetResponse duplicate = channel.basicGet(ProfileMQConfig.PROFILE_QUEUE, false);
            assertThat(duplicate).isNotNull();
            consumer.handle(json.readValue(duplicate.getBody(), ProfileReconcileMessage.class), message(duplicate), channel);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM tb_inbox_event WHERE event_id=?", Integer.class, event.getEventId()))
                    .isEqualTo(1);
        }

        jdbc.update("INSERT INTO tb_content(content_id,content_type,tags,publish_user_id,audit_status,liked) VALUES(1,1,'[\"basketball\"]',123,1,0),(2,1,'[\"football\"]',123,1,0)");
        redis.opsForZSet().add(RedisConstants.RECOMMEND_HOT_ALL_KEY, "1", 100.0);
        redis.opsForZSet().add(RedisConstants.RECOMMEND_HOT_ALL_KEY, "2", 0.0);
        redis.opsForZSet().add(RedisConstants.RECOMMEND_ALL_KEY, "1", 100.0);
        redis.opsForZSet().add(RedisConstants.RECOMMEND_ALL_KEY, "2", 0.0);
        var profile = new UserProfileServiceImpl(contentMapper, redis);
        var rerank = new RecommendRerankServiceImpl(redis, contentMapper, profile, new RecommendProperties());
        assertThat(rerank.rerank(123L, null, 10).contents()).extracting(Content::getContentId)
                .containsExactly(2L, 1L);
    }

    private ResultActions postEvent(String token, BotProfileEventDTO event) throws Exception {
        return mockMvc.perform(post("/bot/profile/events")
                .header("authorization", token)
                .contentType(APPLICATION_JSON)
                .content(json.writeValueAsBytes(event)))
                .andExpect(status().isOk());
    }

    private BotProfileEventDTO event(String valence) {
        BotProfileEventDTO event = new BotProfileEventDTO();
        event.setEventId(UUID.randomUUID().toString());
        event.setUserId(123L);
        event.setMemoryId(UUID.randomUUID().toString());
        event.setPersonaVersion("v1");
        event.setRevision(1L);
        event.setOperation("UPSERT");
        event.setTopics(List.of("basketball"));
        event.setValence(valence);
        return event;
    }

    private String serviceToken() {
        Map<String, Object> claims = new HashMap<>();
        claims.put(JwtClaimsConstant.USER_ID, 10000L);
        claims.put(JwtClaimsConstant.TOKEN_TYPE, JwtClaimsConstant.SERVICE_TOKEN_TYPE);
        return JwtUtil.createJWT("01234567890123456789012345678901", 60_000L, claims);
    }

    private Message message(GetResponse delivery) {
        MessageProperties properties = new MessageProperties();
        properties.setDeliveryTag(delivery.getEnvelope().getDeliveryTag());
        return new Message(delivery.getBody(), properties);
    }

    @TestConfiguration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    @EnableWebMvc
    @EnableWebSecurity
    @EnableMethodSecurity
    @EnableTransactionManagement
    @EnableAspectJAutoProxy
    @MapperScan("com.quanta.demo0.mapper")
    @Import({
            ExplicitPreferenceServiceImpl.class,
            OutboxEventServiceImpl.class,
            InboxEventServiceImpl.class,
            OutboxDispatchProperties.class,
            RecommendProperties.class,
            ReadPathCacheProperties.class,
            JwtProperties.class,
            QuantabotProperties.class,
            SecurityProperties.class,
            AuthenticationSnapshotCacheImpl.class,
            TokenAuthenticationServiceImpl.class,
            OptionalJwtAuthenticationFilter.class,
            SecurityAuthenticationEntryPoint.class,
            SecurityAccessDeniedHandler.class,
            com.quanta.demo0.config.SecurityConfiguration.class,
            BotProfileController.class
    })
    static class TestConfig {
    }
}
