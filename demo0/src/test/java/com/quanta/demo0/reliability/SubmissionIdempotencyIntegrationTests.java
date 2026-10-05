package com.quanta.demo0.reliability;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.quanta.demo0.platform.redis.constant.RedisConstants;
import com.quanta.demo0.platform.security.constant.JwtClaimsConstant;
import com.quanta.demo0.platform.security.utils.JwtUtil;
import com.quanta.demo0.platform.web.idempotency.properties.SubmissionProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * HTTP 提交幂等真实链路验收。
 *
 * <p>应用、安全过滤器、Controller、领域 CommandService、MySQL、Redis、
 * RabbitMQ 和 Outbox 均使用隔离容器；不调用本机已有的 3306/6379 数据，
 * 也不替换领域 Service、Mapper 或 Outbox。测试只覆盖本次幂等改造的关键边界，
 * 不承担全仓回归、压测或付费模型验收。</p>
 */
@Testcontainers
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.rabbitmq.listener.simple.auto-startup=false",
                "spring.task.scheduling.enabled=false",
                "spring.sql.init.mode=never",
                "spring.data.redis.timeout=750ms",
                "spring.data.redis.connect-timeout=750ms",
                "quanta.recommend.warmup-on-startup=false",
                "quanta.recommend.topic-tags.enabled=false",
                "rag.enabled=false",
                "rag.ai-enabled=false",
                "spring.elasticsearch.host=127.0.0.1",
                "spring.elasticsearch.port=1",
                "spring.elasticsearch.scheme=http",
                "spring.ai.openai.api-key=test-only-no-network",
                "spring.ai.qwen-openai.api-key=test-only-no-network",
                "quanta.jwt.user-secret-key=submission-runtime-test-secret-0123456789",
                "quanta.jwt.user-ttl=3600000",
                "quanta.jwt.user-token-name=authorization",
                "quanta.submission-idempotency.require-user-key=true",
                // Content moderation is enabled only to make the real publish path
                // append its moderation Outbox row before response serialization.
                // The listener is disabled above, so this never calls a paid provider.
                "quanta.moderation.enabled=true",
                "quanta.moderation.targets.content.enabled=true",
                "quanta.moderation.targets.content.disabled-policy=PENDING",
                "quanta.moderation.targets.answer.enabled=false",
                "quanta.moderation.targets.answer.disabled-policy=PENDING",
                "quanta.moderation.targets.comment.enabled=false",
                "quanta.moderation.targets.comment.disabled-policy=APPROVED",
                "quanta.wechat.appid=submission-runtime-test",
                "quanta.wechat.secret=submission-runtime-test"
        }
)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class SubmissionIdempotencyIntegrationTests {

    private static final String TEST_SECRET =
            "submission-runtime-test-secret-0123456789";
    private static final long AUTHOR_ID = 2001L;
    private static final long VIEWER_ID = 2002L;
    private static final long OTHER_USER_ID = 2003L;
    private static final long QUESTION_ID = 91001L;
    private static final long COMMENT_CONTENT_ID = 91002L;

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0.43")
            .withDatabaseName("demo")
            .withUsername("demo")
            .withPassword("demo")
            .withInitScript("db/submission-runtime-schema.sql");

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>(
            DockerImageName.parse("redis:7.2-alpine"))
            .withExposedPorts(6379);

    @Container
    static final RabbitMQContainer RABBIT = new RabbitMQContainer(
            DockerImageName.parse("rabbitmq:3.13-management-alpine"))
            .withAdminUser("runtime")
            .withAdminPassword("runtime");

    @DynamicPropertySource
    static void registerContainerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.datasource.driver-class-name", MYSQL::getDriverClassName);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", REDIS::getFirstMappedPort);
        registry.add("spring.data.redis.database", () -> 0);
        registry.add("spring.rabbitmq.host", RABBIT::getHost);
        registry.add("spring.rabbitmq.port", RABBIT::getAmqpPort);
        registry.add("spring.rabbitmq.username", RABBIT::getAdminUsername);
        registry.add("spring.rabbitmq.password", RABBIT::getAdminPassword);
        registry.add("spring.rabbitmq.virtual-host", () -> "/");
    }

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private SubmissionProperties submissionProperties;

    /** Prevent unrelated startup warmers from contacting external services. */
    @MockitoBean
    private com.quanta.demo0.feed.config.RecommendFeedInitializer recommendFeedInitializer;

    @MockitoBean
    private com.quanta.demo0.search.es.initializer.ElasticsearchIndexInitializer
            elasticsearchIndexInitializer;

    @MockitoBean
    private com.quanta.demo0.rag.vector.VectorStoreInitializer vectorStoreInitializer;

    @BeforeEach
    void resetIsolatedData() {
        clearRedis();
        jdbcTemplate.update("DELETE FROM tb_http_submission");
        jdbcTemplate.update("DELETE FROM tb_comment_like");
        jdbcTemplate.update("DELETE FROM tb_browse_history");
        jdbcTemplate.update("DELETE FROM tb_comment_image");
        jdbcTemplate.update("DELETE FROM tb_content_comment");
        jdbcTemplate.update("DELETE FROM tb_content_image");
        jdbcTemplate.update("DELETE FROM tb_question_answer");
        jdbcTemplate.update("DELETE FROM tb_outbox_event");
        jdbcTemplate.update("DELETE FROM tb_notification");
        jdbcTemplate.update("DELETE FROM user_role");
        jdbcTemplate.update("DELETE FROM tb_user_auth");
        jdbcTemplate.update("DELETE FROM tb_content");
        jdbcTemplate.update("DELETE FROM tb_user");

        LocalDateTime now = LocalDateTime.now();
        jdbcTemplate.update(
                """
                INSERT INTO tb_user
                    (id, openid, nick_name, avatar_url, auth_status,
                     create_time, update_time, is_deleted, is_admin, account_status)
                VALUES
                    (?, ?, ?, ?, 2, ?, ?, 0, 0, 0),
                    (?, ?, ?, ?, 2, ?, ?, 0, 0, 0),
                    (?, ?, ?, ?, 2, ?, ?, 0, 0, 0)
                """,
                AUTHOR_ID, "submission-author", "提交作者", "https://example.test/a.png", now, now,
                VIEWER_ID, "submission-viewer", "提交访问者", "https://example.test/v.png", now, now,
                OTHER_USER_ID, "submission-other", "隔离访问者", "https://example.test/o.png", now, now
        );
        jdbcTemplate.update(
                """
                INSERT INTO tb_user_auth
                    (user_id, identity_type, real_name, school_id, quanta_batch,
                     quanta_department, audit_status, create_time, update_time)
                VALUES
                    (?, 1, ?, ?, '2026', 'runtime', 1, ?, ?),
                    (?, 1, ?, ?, '2026', 'runtime', 1, ?, ?),
                    (?, 1, ?, ?, '2026', 'runtime', 1, ?, ?)
                """,
                AUTHOR_ID, "作者", "submission-author", now, now,
                VIEWER_ID, "访问者", "submission-viewer", now, now,
                OTHER_USER_ID, "其他访问者", "submission-other", now, now
        );
        jdbcTemplate.update(
                """
                INSERT INTO tb_content
                    (content_id, content_type, title, content, publish_user_id,
                     audit_status, liked, comment_count, collect_count,
                     create_time, update_time, is_deleted)
                VALUES
                    (?, 2, '幂等问题', '用于回答幂等验收的问题', ?, 1, 0, 0, 0, ?, ?, 0),
                    (?, 1, '幂等评论帖子', '用于评论与回复幂等验收的帖子', ?, 1, 0, 0, 0, ?, ?, 0)
                """,
                QUESTION_ID, AUTHOR_ID, now, now,
                COMMENT_CONTENT_ID, AUTHOR_ID, now, now
        );
    }

    @AfterEach
    void clearRedisAfterTest() {
        clearRedis();
    }

    @Test
    void sameTokenReplaysOriginalIdAcrossContentAnswerAndCommentWithoutNewOutbox() {
        String authorToken = normalToken(AUTHOR_ID);
        String viewerToken = normalToken(VIEWER_ID);

        Map<String, Object> content = new HashMap<>();
        content.put("contentType", 1);
        content.put("title", "一次提交的帖子");
        content.put("content", "同一个凭证重复发送只能创建一条帖子");
        content.put("images", List.of());
        String contentKey = UUID.randomUUID().toString();
        int contentRowsBefore = count("tb_content", "publish_user_id", AUTHOR_ID);
        int outboxBeforeContent = outboxCount();
        ResponseEntity<String> missingKey = postWithoutIdempotency(
                "/content/publish", authorToken, content);
        assertThat(missingKey.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(count("tb_content", "publish_user_id", AUTHOR_ID))
                .isEqualTo(contentRowsBefore);
        ResponseEntity<String> firstContent = post(
                "/content/publish", authorToken, contentKey, content);
        int outboxAfterFirstContent = outboxCount();
        Map<String, Object> changedContent = new HashMap<>(content);
        changedContent.put("content", "同一个凭证不能改成另一份请求");
        ResponseEntity<String> conflictingContent = post(
                "/content/publish", authorToken, contentKey, changedContent);
        assertThat(conflictingContent.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(count("tb_content", "publish_user_id", AUTHOR_ID))
                .isEqualTo(contentRowsBefore + 1);
        assertThat(outboxCount()).isEqualTo(outboxAfterFirstContent);
        ResponseEntity<String> replayContent = post(
                "/content/publish", authorToken, contentKey, content);
        assertSuccessReplay(firstContent, replayContent);
        assertThat(count("tb_content", "publish_user_id", AUTHOR_ID))
                .isEqualTo(contentRowsBefore + 1);
        assertStableOutbox(outboxBeforeContent, outboxAfterFirstContent, outboxCount());

        Map<String, Object> answer = Map.of(
                "questionId", QUESTION_ID,
                "content", "一次提交的回答");
        String answerKey = UUID.randomUUID().toString();
        int outboxBeforeAnswer = outboxCount();
        ResponseEntity<String> firstAnswer = post(
                "/answer/publish", viewerToken, answerKey, answer);
        int outboxAfterFirstAnswer = outboxCount();
        ResponseEntity<String> replayAnswer = post(
                "/answer/publish", viewerToken, answerKey, answer);
        assertSuccessReplay(firstAnswer, replayAnswer);
        assertThat(count("tb_question_answer", "user_id", VIEWER_ID)).isEqualTo(1);
        assertStableOutbox(outboxBeforeAnswer, outboxAfterFirstAnswer, outboxCount());

        Map<String, Object> comment = new HashMap<>();
        comment.put("contentId", COMMENT_CONTENT_ID);
        comment.put("content", "一次提交的普通评论");
        comment.put("imageUrls", List.of());
        comment.put("mentionBot", false);
        String commentKey = UUID.randomUUID().toString();
        int outboxBeforeComment = outboxCount();
        ResponseEntity<String> firstComment = post(
                "/comment/send", viewerToken, commentKey, comment);
        int outboxAfterFirstComment = outboxCount();
        ResponseEntity<String> replayComment = post(
                "/comment/send", viewerToken, commentKey, comment);
        assertSuccessReplay(firstComment, replayComment);
        assertThat(count("tb_content_comment", "user_id", VIEWER_ID)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT audit_status FROM tb_content_comment LIMIT 1", Integer.class))
                .isEqualTo(1);
        assertThat(outboxAfterFirstComment).isGreaterThan(outboxBeforeComment);
        assertStableOutbox(outboxBeforeComment, outboxAfterFirstComment, outboxCount());

        ResponseEntity<String> visibleComments = get(
                "/comment/list?contentId=" + COMMENT_CONTENT_ID
                        + "&pageNum=1&pageSize=10",
                viewerToken,
                UUID.randomUUID().toString());
        assertThat(visibleComments.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode visibleList = json(visibleComments).path("data").path("list");
        assertThat(visibleList.size()).isEqualTo(1);
        assertThat(visibleList.get(0).path("commentId").asLong())
                .isEqualTo(rootResponseBody(firstComment).asLong());
    }

    @Test
    void sameTokenReplaysOneFloorReply() {
        String authorToken = normalToken(AUTHOR_ID);
        String viewerToken = normalToken(VIEWER_ID);
        Map<String, Object> root = new HashMap<>();
        root.put("contentId", COMMENT_CONTENT_ID);
        root.put("content", "楼层根评论");
        root.put("imageUrls", List.of());
        root.put("mentionBot", false);
        ResponseEntity<String> rootResponse = post(
                "/comment/send", authorToken, UUID.randomUUID().toString(), root);
        assertThat(rootResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        long rootId = rootResponseBody(rootResponse).asLong();

        Map<String, Object> reply = new HashMap<>();
        reply.put("contentId", COMMENT_CONTENT_ID);
        reply.put("parentId", rootId);
        reply.put("replyCommentId", rootId);
        reply.put("replyUserId", AUTHOR_ID);
        reply.put("content", "同一楼层回复的重试");
        reply.put("imageUrls", List.of());
        reply.put("mentionBot", false);
        String replyKey = UUID.randomUUID().toString();
        int outboxBefore = outboxCount();
        ResponseEntity<String> first = post(
                "/comment/send", viewerToken, replyKey, reply);
        int outboxAfterFirst = outboxCount();
        ResponseEntity<String> replay = post(
                "/comment/send", viewerToken, replyKey, reply);

        assertSuccessReplay(first, replay);
        assertThat(rootResponseBody(first).asLong())
                .isNotEqualTo(rootId);
        assertThat(countReplies(rootId)).isEqualTo(1);
        assertThat(outboxAfterFirst).isGreaterThanOrEqualTo(outboxBefore);
        assertThat(outboxCount()).isEqualTo(outboxAfterFirst);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM tb_http_submission WHERE scene='comment-send'",
                Integer.class)).isEqualTo(2);
    }

    @Test
    void concurrentSameTokenCreatesAtMostOneBusinessRow() throws Exception {
        String token = normalToken(AUTHOR_ID);
        Map<String, Object> body = new HashMap<>();
        body.put("contentType", 1);
        body.put("title", "并发幂等帖子");
        body.put("content", "两个真实 HTTP 请求共享同一个提交凭证");
        body.put("images", List.of());
        String key = UUID.randomUUID().toString();
        int contentRowsBefore = count("tb_content", "publish_user_id", AUTHOR_ID);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<CompletableFuture<ResponseEntity<String>>> futures = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                futures.add(CompletableFuture.supplyAsync(() -> {
                    ready.countDown();
                    await(start);
                    return post("/content/publish", token, key, body);
                }, executor));
            }
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            List<ResponseEntity<String>> responses = futures.stream()
                    .map(future -> future.join())
                    .toList();
            assertThat(responses).anyMatch(response -> response.getStatusCode() == HttpStatus.OK);
            assertThat(responses).allMatch(response -> response.getStatusCode() == HttpStatus.OK
                    || response.getStatusCode() == HttpStatus.CONFLICT);
            assertThat(count("tb_content", "publish_user_id", AUTHOR_ID))
                    .isEqualTo(contentRowsBefore + 1);
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM tb_http_submission "
                            + "WHERE user_id=? AND scene='content-publish' AND submission_token=?",
                    Integer.class, AUTHOR_ID, key)).isEqualTo(1);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void domainFailureRollsBackCredentialBusinessRowAndOutbox() {
        String token = normalToken(AUTHOR_ID);
        Map<String, Object> valid = new HashMap<>();
        valid.put("contentType", 1);
        valid.put("title", "响应保存失败也要回滚");
        valid.put("content", "业务和审核 Outbox 已写入后，幂等响应过大应让事务整体回滚");
        valid.put("images", List.of());
        String invalidKey = UUID.randomUUID().toString();
        int contentRowsBefore = count("tb_content", "publish_user_id", AUTHOR_ID);
        int outboxBefore = outboxCount();

        int previousMaxResponseBytes = submissionProperties.getMaxResponseBytes();
        try {
            submissionProperties.setMaxResponseBytes(1);
            ResponseEntity<String> failure = post(
                    "/content/publish", token, invalidKey, valid);
            assertThat(failure.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        } finally {
            submissionProperties.setMaxResponseBytes(previousMaxResponseBytes);
        }
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM tb_http_submission", Integer.class)).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM tb_content WHERE publish_user_id=?",
                Integer.class, AUTHOR_ID)).isEqualTo(contentRowsBefore);
        assertThat(outboxCount()).isEqualTo(outboxBefore);

        ResponseEntity<String> retry = post(
                "/content/publish", token, invalidKey, valid);
        assertThat(retry.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(count("tb_content", "publish_user_id", AUTHOR_ID))
                .isEqualTo(contentRowsBefore + 1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM tb_http_submission", Integer.class)).isEqualTo(1);
        assertThat(outboxCount()).isGreaterThan(outboxBefore);
    }

    @Test
    void lostRedisPlaceholderFallsBackToDatabaseAndStatusIsUserIsolated() {
        String token = normalToken(AUTHOR_ID);
        Map<String, Object> body = Map.of(
                "contentType", 1,
                "title", "Redis 占位丢失后的提交",
                "content", "数据库凭证仍应复用原结果",
                "images", List.of());
        String key = UUID.randomUUID().toString();
        ResponseEntity<String> first = post("/content/publish", token, key, body);
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);
        long id = contentResponseId(first);

        redisTemplate.delete("submission:processing:" + AUTHOR_ID
                + ":content-publish:" + key);
        int rowsBeforeReplay = count("tb_content", "publish_user_id", AUTHOR_ID);
        ResponseEntity<String> replay = post("/content/publish", token, key, body);
        assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(contentResponseId(replay)).isEqualTo(id);
        assertThat(count("tb_content", "publish_user_id", AUTHOR_ID)).isEqualTo(rowsBeforeReplay);

        String otherToken = normalToken(OTHER_USER_ID);
        ResponseEntity<String> hidden = get(
                "/submission/status?scene=content-publish", otherToken, key);
        assertThat(hidden.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode hiddenBody = json(hidden);
        assertThat(hiddenBody.path("data").path("status").asText())
                .isEqualTo("UNCONFIRMED");
        assertThat(hiddenBody.path("data").path("data").isNull()).isTrue();

        ResponseEntity<String> status = get(
                "/submission/status?scene=content-publish", token, key);
        assertThat(status.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode statusData = json(status).path("data");
        assertThat(statusData.path("status").asText()).isEqualTo("SUCCEEDED");
        assertThat(statusData.path("data").path("contentId").asLong()).isEqualTo(id);

        ResponseEntity<String> anonymous = getAnonymous(
                "/submission/status?scene=content-publish", key);
        assertThat(anonymous.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    private void assertSuccessReplay(
            ResponseEntity<String> first,
            ResponseEntity<String> replay
    ) {
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(rootResponseBody(replay)).isEqualTo(rootResponseBody(first));
    }

    private void assertStableOutbox(int before, int afterFirst, int afterReplay) {
        assertThat(afterFirst).isGreaterThanOrEqualTo(before);
        assertThat(afterReplay).isEqualTo(afterFirst);
    }

    private ResponseEntity<String> post(
            String path,
            String token,
            String submissionToken,
            Object body
    ) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("authorization", token);
        headers.set("Idempotency-Key", submissionToken);
        return restTemplate.exchange(
                path,
                HttpMethod.POST,
                new HttpEntity<>(body, headers),
                String.class
        );
    }

    private ResponseEntity<String> postWithoutIdempotency(
            String path,
            String token,
            Object body
    ) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("authorization", token);
        return restTemplate.exchange(
                path,
                HttpMethod.POST,
                new HttpEntity<>(body, headers),
                String.class
        );
    }

    private ResponseEntity<String> get(
            String path,
            String token,
            String submissionToken
    ) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("authorization", token);
        headers.set("Idempotency-Key", submissionToken);
        return restTemplate.exchange(
                path,
                HttpMethod.GET,
                new HttpEntity<>(headers),
                String.class
        );
    }

    private ResponseEntity<String> getAnonymous(String path, String submissionToken) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Idempotency-Key", submissionToken);
        return restTemplate.exchange(
                path,
                HttpMethod.GET,
                new HttpEntity<>(headers),
                String.class
        );
    }

    private JsonNode json(ResponseEntity<String> response) {
        try {
            return objectMapper.readTree(response.getBody());
        } catch (Exception exception) {
            throw new AssertionError("response was not JSON: " + response.getBody(), exception);
        }
    }

    private JsonNode rootResponseBody(ResponseEntity<String> response) {
        return json(response).path("data");
    }

    private long contentResponseId(ResponseEntity<String> response) {
        return rootResponseBody(response).path("contentId").asLong();
    }

    private int count(String table, String column, long value) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM " + table + " WHERE " + column + "=?",
                Integer.class,
                value);
        return count == null ? 0 : count;
    }

    private int countReplies(long parentId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM tb_content_comment WHERE parent_id=? AND is_deleted=0",
                Integer.class,
                parentId);
        return count == null ? 0 : count;
    }

    private int outboxCount() {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM tb_outbox_event", Integer.class);
        return count == null ? 0 : count;
    }

    private String normalToken(long userId) {
        String token = JwtUtil.createJWT(
                TEST_SECRET,
                3_600_000L,
                Map.of(JwtClaimsConstant.USER_ID, userId));
        redisTemplate.opsForValue().set(
                RedisConstants.LOGIN_USER_KEY + userId,
                token);
        return token;
    }

    private void clearRedis() {
        redisTemplate.getConnectionFactory().getConnection().serverCommands().flushDb();
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("concurrent request did not start in time");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted while starting concurrent requests", exception);
        }
    }
}
