package com.quanta.demo0.reliability;
import com.quanta.demo0.platform.mq.properties.OutboxDispatchProperties;


import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.quanta.demo0.config.ProfileMQConfig;
import com.quanta.demo0.config.TopicTagMQConfig;
import com.quanta.demo0.constant.RedisConstants;
import com.quanta.demo0.dto.BotProfileEventDTO;
import com.quanta.demo0.content.entity.Content;
import com.quanta.demo0.mapper.ContentMapper;
import com.quanta.demo0.mq.consumer.ProfileReconcileConsumer;
import com.quanta.demo0.mq.consumer.ContentTopicTagConsumer;
import com.quanta.demo0.mq.message.ProfileReconcileMessage;
import com.quanta.demo0.mq.message.ContentTopicTagMessage;
import com.quanta.demo0.mq.outbox.OutboxDispatcher;
import com.quanta.demo0.mq.outbox.OutboxRouteRegistry;
import com.quanta.demo0.mq.producer.ProfileReconcileProducer;
import com.quanta.demo0.mq.producer.ContentTopicTagProducer;
import com.quanta.demo0.mq.producer.ReliableRabbitPublisher;
import com.quanta.demo0.properties.*;
import com.quanta.demo0.service.*;
import com.quanta.demo0.service.Impl.*;
import com.rabbitmq.client.GetResponse;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mybatis.spring.boot.test.autoconfigure.MybatisTest;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.*;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.mock;
import com.quanta.demo0.content.properties.ContentTopicProperties;

/** 真 MySQL/RabbitMQ/Redis 定向闭环；隔离容器，不修改真实用户。付费标签 smoke 显式开启，仅一帖。 */
@MybatisTest @Testcontainers
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import({ExplicitPreferenceServiceImpl.class, OutboxEventServiceImpl.class, InboxEventServiceImpl.class,
        OutboxDispatchProperties.class, RecommendProperties.class, ContentTopicProperties.class,
        ContentTopicTagServiceImpl.class, ProfilePreferenceIntegrationTests.TestBeans.class})
class ProfilePreferenceIntegrationTests {
    @Container static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0.43")
            .withDatabaseName("demo").withUsername("demo").withPassword("demo")
            .withInitScript("db/reliability-test-schema.sql");
    @Container static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7.2-alpine").withExposedPorts(6379);
    @Container static final RabbitMQContainer RABBIT = new RabbitMQContainer("rabbitmq:3.13-management");
    @DynamicPropertySource static void dataSource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.datasource.driver-class-name", MYSQL::getDriverClassName);
        registry.add("mybatis.mapper-locations", () -> "classpath:mapper/*.xml");
    }
    @Autowired JdbcTemplate jdbc;
    @Autowired ExplicitPreferenceService preferences;
    @Autowired OutboxEventService outbox;
    @Autowired InboxEventService inbox;
    @Autowired ObjectMapper json;
    @Autowired StringRedisTemplate redis;
    @Autowired PlatformTransactionManager transactions;
    @Autowired ContentMapper contentMapper;
    @Autowired ContentTopicTagService topicTags;
    @Autowired ContentTopicProperties topicProperties;
    private CachingConnectionFactory rabbitConnection;
    private RabbitTemplate rabbit;
    private OutboxDispatcher dispatcher;

    @BeforeEach void prepare() {
        // 在隔离 MySQL 执行完整生产迁移两次，验证旧表补列和可重复执行。
        jdbc.execute("ALTER TABLE tb_content DROP COLUMN tags");
        try (var connection = jdbc.getDataSource().getConnection()) {
            var migration = new org.springframework.core.io.ClassPathResource("db/V_recommend_topics_profile.sql");
            org.springframework.jdbc.datasource.init.ScriptUtils.executeSqlScript(connection,migration);
            org.springframework.jdbc.datasource.init.ScriptUtils.executeSqlScript(connection,migration);
        } catch (java.sql.SQLException exception) { throw new IllegalStateException(exception); }
        jdbc.update("DELETE FROM tb_inbox_event");
        jdbc.update("DELETE FROM tb_outbox_event");
        jdbc.update("DELETE FROM tb_user_profile_signal");
        jdbc.update("DELETE FROM tb_user");
        jdbc.update("DELETE FROM tb_content");
        jdbc.update("INSERT INTO tb_user(id,nick_name) VALUES(123,'profile-test')");
        redis.delete(List.of("user:profile-explicit:123", "recommend:exposed:123"));
        rabbitConnection = new CachingConnectionFactory(RABBIT.getHost(), RABBIT.getAmqpPort());
        rabbitConnection.setUsername(RABBIT.getAdminUsername());
        rabbitConnection.setPassword(RABBIT.getAdminPassword());
        rabbitConnection.setPublisherConfirmType(CachingConnectionFactory.ConfirmType.CORRELATED);
        rabbitConnection.setPublisherReturns(true);
        RabbitAdmin admin = new RabbitAdmin(rabbitConnection);
        for (var declaration : new ProfileMQConfig().profileTopology().getDeclarables()) {
            if (declaration instanceof org.springframework.amqp.core.Queue queue) { admin.declareQueue(queue); }
            else if (declaration instanceof org.springframework.amqp.core.Exchange exchange) { admin.declareExchange(exchange); }
            else if (declaration instanceof org.springframework.amqp.core.Binding binding) { admin.declareBinding(binding); }
        }
        var tags = new TopicTagMQConfig();
        admin.declareExchange(tags.topicTagExchange()); admin.declareQueue(tags.topicTagQueue()); admin.declareBinding(tags.topicTagBinding());
        admin.purgeQueue(ProfileMQConfig.PROFILE_QUEUE); admin.purgeQueue(TopicTagMQConfig.TOPIC_TAG_QUEUE);
        rabbit = new RabbitTemplate(rabbitConnection); rabbit.setMandatory(true);
        rabbit.setMessageConverter(new Jackson2JsonMessageConverter(json));
        dispatcher = new OutboxDispatcher();
        ReflectionTestUtils.setField(dispatcher,"rabbitTemplate",rabbit);
        ReflectionTestUtils.setField(dispatcher,"outboxEventService",outbox);
        ReflectionTestUtils.setField(dispatcher,"outboxRouteRegistry",new OutboxRouteRegistry(json));
        ReflectionTestUtils.setField(dispatcher,"outboxDispatchProperties",new OutboxDispatchProperties());
    }
    @AfterEach void close() { if (rabbitConnection != null) { rabbitConnection.destroy(); } }

    @Test void realTransportDuplicateDeleteAndLateUpsertProduceCurrentSnapshotAndRank() throws Exception {
        BotProfileEventDTO add = event(100L,"UPSERT","negative");
        assertThat(preferences.accept(add)).isTrue();
        assertThat(preferences.accept(add)).isFalse();
        dispatchAndConsumeProfile();
        assertThat(redis.opsForHash().get("user:profile-explicit:123","basketball")).isEqualTo("-1.25");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM tb_user_profile_signal",Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT status FROM tb_outbox_event WHERE event_id=?",String.class,add.getEventId())).isEqualTo("SENT");
        assertThat(jdbc.queryForObject("SELECT status FROM tb_inbox_event WHERE event_id=?",String.class,add.getEventId())).isEqualTo("SUCCESS");
        jdbc.update("INSERT INTO tb_content(content_id,content_type,tags,publish_user_id,audit_status,liked) VALUES(1,1,'[\"basketball\"]',123,1,100),(2,1,'[\"football\"]',123,1,0)");
        redis.opsForZSet().add(RedisConstants.RECOMMEND_HOT_ALL_KEY,"1",100.0);
        redis.opsForZSet().add(RedisConstants.RECOMMEND_HOT_ALL_KEY,"2",0.0);
        var profile = new UserProfileServiceImpl(contentMapper,redis);
        var rerank = new RecommendRerankServiceImpl(redis,contentMapper,profile,new RecommendProperties());
        assertThat(rerank.rerank(123L,null,10).contents()).extracting(Content::getContentId).containsExactly(2L,1L);
        // 删除后重新投递旧 UPSERT（新的运输ID，旧版本），不能复活篮球负偏好。
        assertThat(preferences.accept(event(200L,"DELETE",null))).isTrue();
        dispatchAndConsumeProfile();
        assertThat(redis.opsForHash().hasKey("user:profile-explicit:123","basketball")).isFalse();
        redis.delete("recommend:exposed:123");
        assertThat(rerank.rerank(123L,null,10).contents()).extracting(Content::getContentId).containsExactly(1L,2L);
        assertThat(preferences.accept(event(150L,"UPSERT","positive"))).isTrue();
        dispatchAndConsumeProfile();
        assertThat(redis.opsForHash().hasKey("user:profile-explicit:123","basketball")).isFalse();
        // 新版本再次明确喜欢后恢复；真实画像读层不把版本混入兴趣分母。
        assertThat(preferences.accept(event(300L,"UPSERT","positive"))).isTrue();
        dispatchAndConsumeProfile();
        assertThat(new UserProfileServiceImpl(contentMapper,redis).getExplicitProfile(123L))
                .containsExactlyEntriesOf(java.util.Map.of("basketball",0.75));
    }

    @Test void preferenceAndOutboxRollBackTogether() {
        assertThatThrownBy(() -> new TransactionTemplate(transactions).execute(status -> {
            preferences.accept(event(100L,"UPSERT","positive"));
            throw new IllegalStateException("test rollback");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM tb_user_profile_signal",Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM tb_outbox_event",Integer.class)).isZero();
    }

    @Test void repeatedTopicRegistrationCreatesOnlyOnePersistentTask() {
        String original = outbox.createContentTopicTagEvent(9001L);
        assertThat(outbox.createContentTopicTagEvent(9001L)).isEqualTo(original);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM tb_outbox_event",Integer.class)).isEqualTo(1);
    }

    @Test @EnabledIfEnvironmentVariable(named="QUANTA_TOPIC_SMOKE",matches="1")
    void oneRealModelBackfillTravelsThroughOutboxInboxAndConditionalTagWrite() throws Exception {
        jdbc.update("INSERT INTO tb_content(content_id,content_type,title,content,publish_user_id,audit_status) VALUES(9001,2,?,?,123,1)",
                "我的课程复习经验", "分享我的学习经验：先整理课程知识点，再练习历年期末考试题，最后复盘易错题。");
        topicProperties.setTimeoutSeconds(60);
        assertThat(topicTags.enqueueBackfill(0L,1)).isEqualTo(9001L);
        dispatcher.dispatch();
        try (var channel = rabbitConnection.createConnection().createChannel(false)) {
            GetResponse delivery = channel.basicGet(TopicTagMQConfig.TOPIC_TAG_QUEUE,false);
            assertThat(delivery).isNotNull();
            var message = message(delivery);
            var publisher = new ReliableRabbitPublisher(rabbit,new OutboxDispatchProperties());
            var consumer = new ContentTopicTagConsumer(inbox,topicTags,new ContentTopicTagProducer(publisher),topicProperties);
            ContentTopicTagMessage event = json.readValue(delivery.getBody(),ContentTopicTagMessage.class);
            consumer.handle(event,message,channel);
            Content tagged = contentMapper.selectById(9001L);
            assertThat(TopicCatalog.parseStoredTags(tagged.getTags())).contains("experience_sharing","course_study").hasSizeLessThanOrEqualTo(3);
            assertThat(jdbc.queryForObject("SELECT status FROM tb_inbox_event WHERE event_id=?",String.class,event.getEventId())).isEqualTo("SUCCESS");
            assertThat(jdbc.queryForObject("SELECT status FROM tb_outbox_event WHERE event_id=?",String.class,event.getEventId())).isEqualTo("SENT");
            assertThat(topicTags.enqueueBackfill(0L,1)).isZero();
        }
    }

    private void dispatchAndConsumeProfile() throws Exception {
        dispatcher.dispatch();
        try (var channel = rabbitConnection.createConnection().createChannel(false)) {
            GetResponse delivery = channel.basicGet(ProfileMQConfig.PROFILE_QUEUE,false);
            assertThat(delivery).isNotNull();
            var consumer = new ProfileReconcileConsumer(inbox,preferences,
                    new ProfileReconcileProducer(new ReliableRabbitPublisher(rabbit,new OutboxDispatchProperties())));
            consumer.handle(json.readValue(delivery.getBody(),ProfileReconcileMessage.class),message(delivery),channel);
        }
    }
    private Message message(GetResponse delivery) {
        MessageProperties properties = new MessageProperties(); properties.setDeliveryTag(delivery.getEnvelope().getDeliveryTag());
        return new Message(delivery.getBody(),properties);
    }
    private BotProfileEventDTO event(long revision,String operation,String valence) {
        BotProfileEventDTO dto = new BotProfileEventDTO(); dto.setEventId(UUID.randomUUID().toString()); dto.setUserId(123L);
        dto.setMemoryId("6110e1d664174ad8a67a73cb49b28edc"); dto.setPersonaVersion("v1"); dto.setRevision(revision);
        dto.setOperation(operation); dto.setValence(valence); dto.setTopics("DELETE".equals(operation)?List.of():List.of("basketball"));
        return dto;
    }

    @TestConfiguration static class TestBeans {
        @Bean ObjectMapper objectMapper() { return new ObjectMapper().registerModule(new JavaTimeModule()); }
        @Bean(destroyMethod="destroy") LettuceConnectionFactory redisConnection() {
            return new LettuceConnectionFactory(REDIS.getHost(),REDIS.getMappedPort(6379));
        }
        @Bean StringRedisTemplate redisTemplate(LettuceConnectionFactory connection) { return new StringRedisTemplate(connection); }
        @Bean ChatModel chatModel() {
            if (!"1".equals(System.getenv("QUANTA_TOPIC_SMOKE"))) { return mock(ChatModel.class); }
            String key = System.getenv("OPENAI_API_KEY");
            if (key == null || key.isBlank()) { throw new IllegalStateException("真实标签 smoke 缺少API凭据"); }
            String model = System.getenv().getOrDefault("TOPIC_SMOKE_MODEL","deepseek-chat");
            return OpenAiChatModel.builder().openAiApi(new OpenAiApi("https://api.deepseek.com",key))
                    .defaultOptions(OpenAiChatOptions.builder().model(model).temperature(0.0).maxTokens(4096).build()).build();
        }
    }
}
