package com.quanta.demo0.reliability;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.quanta.demo0.platform.security.constant.JwtClaimsConstant;
import com.quanta.demo0.platform.redis.constant.RedisConstants;
import com.quanta.demo0.platform.security.constant.RoleConstants;
import com.quanta.demo0.identity.dto.IdentityAuditDTO;
import com.quanta.demo0.identity.mapper.IdentityMapper;
import com.quanta.demo0.platform.security.context.BaseContext;
import com.quanta.demo0.content.mapper.ContentMapper;
import com.quanta.demo0.user.mapper.UserMapper;
import com.quanta.demo0.platform.security.mapper.UserRoleMapper;
import com.quanta.demo0.platform.security.service.AuthenticationSnapshotCache;
import com.quanta.demo0.platform.security.model.AuthenticationSnapshot;
import com.quanta.demo0.platform.security.model.AuthenticatedUser;
import com.quanta.demo0.platform.security.service.AdminRoleService;
import com.quanta.demo0.user.service.AdminUserService;
import com.quanta.demo0.user.service.AuthorProfileCache;
import com.quanta.demo0.content.service.ContentDetailCacheService;
import com.quanta.demo0.content.service.ContentDetailCacheInvalidator;
import com.quanta.demo0.identity.service.IdentityExamService;
import com.quanta.demo0.content.service.impl.ContentDetailDataLoader;
import com.quanta.demo0.content.service.impl.ContentDetailCacheServiceImpl;
import com.quanta.demo0.platform.security.utils.JwtUtil;
import com.quanta.demo0.platform.common.enums.AuditStatus;
import com.quanta.demo0.platform.redis.properties.ReadPathCacheProperties;
import com.quanta.demo0.content.vo.ContentDetailCacheEntry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.messaging.converter.StringMessageConverter;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.net.URI;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * True-stack read-path cache acceptance.
 *
 * <p>The application, HTTP security filters, production controllers/services,
 * MyBatis mappers, MySQL, Redis and STOMP endpoint all run against disposable
 * containers. Only unrelated AI/ES/recommendation startup work is disabled.
 * This class never points at the developer's existing 9191/3306/6379
 * processes.</p>
 */
@Testcontainers
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.rabbitmq.listener.simple.auto-startup=false",
                "spring.task.scheduling.enabled=false",
                "spring.data.redis.timeout=750ms",
                "spring.data.redis.connect-timeout=750ms",
                "quanta.recommend.warmup-on-startup=false",
                "rag.enabled=false",
                "rag.ai-enabled=false",
                "spring.sql.init.mode=never",
                "quanta.jwt.user-secret-key=read-path-cache-runtime-test-secret-0123456789",
                "quanta.jwt.user-ttl=3600000",
                "quanta.jwt.user-token-name=authorization",
                "quanta.wechat.appid=runtime-test",
                "quanta.wechat.secret=runtime-test",
                "spring.elasticsearch.host=127.0.0.1",
                "spring.elasticsearch.port=1",
                "spring.elasticsearch.scheme=http"
        }
)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ReadPathCacheRuntimeIntegrationTests {

    private static final String TEST_SECRET =
            "read-path-cache-runtime-test-secret-0123456789";
    private static final long VIEWER_ID = 201L;
    private static final long SECOND_VIEWER_ID = 202L;
    private static final long AUTHOR_ID = 200L;
    private static final long BOT_ID = 10000L;
    private static final long CONTENT_ID = 91001L;
    private static final long SECOND_CONTENT_ID = 91002L;

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0.43")
            .withDatabaseName("demo")
            .withUsername("demo")
            .withPassword("demo")
            .withInitScript("db/read-path-cache-runtime-schema.sql");

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
        registry.add("spring.rabbitmq.username", () -> "runtime");
        registry.add("spring.rabbitmq.password", () -> "runtime");
        registry.add("spring.rabbitmq.virtual-host", () -> "/");
    }

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private AuthenticationSnapshotCache authenticationSnapshotCache;

    @Autowired
    private AuthorProfileCache authorProfileCache;

    @Autowired
    private ContentDetailCacheService contentDetailCacheService;

    @Autowired
    private ContentDetailCacheInvalidator contentDetailCacheInvalidator;

    @Autowired
    private ContentDetailDataLoader contentDetailDataLoader;

    @Autowired
    private AdminRoleService adminRoleService;

    @Autowired
    private AdminUserService adminUserService;

    @Autowired
    private IdentityExamService identityExamService;

    @Autowired
    private ReadPathCacheProperties readPathCacheProperties;

    /** Real MyBatis mapper proxies wrapped only to count calls; invocations execute normally. */
    @MockitoSpyBean
    private UserMapper userMapperSpy;

    @MockitoSpyBean
    private IdentityMapper identityMapperSpy;

    @MockitoSpyBean
    private UserRoleMapper userRoleMapperSpy;

    @MockitoSpyBean
    private ContentMapper contentMapperSpy;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @LocalServerPort
    private int port;

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
        authenticationSnapshotCache.evict(VIEWER_ID);
        authenticationSnapshotCache.evict(SECOND_VIEWER_ID);
        authenticationSnapshotCache.evict(AUTHOR_ID);
        authenticationSnapshotCache.evict(BOT_ID);
        authorProfileCache.evict(AUTHOR_ID);
        authorProfileCache.evict(BOT_ID);
        contentDetailCacheService.evict(CONTENT_ID);

        jdbcTemplate.update("DELETE FROM tb_content_image");
        jdbcTemplate.update("DELETE FROM tb_content_like");
        jdbcTemplate.update("DELETE FROM tb_content_collect");
        jdbcTemplate.update("DELETE FROM tb_browse_history");
        jdbcTemplate.update("DELETE FROM tb_user_follow");
        jdbcTemplate.update("DELETE FROM tb_user_auth");
        jdbcTemplate.update("DELETE FROM user_role");
        jdbcTemplate.update("DELETE FROM tb_content");
        jdbcTemplate.update("DELETE FROM tb_question_answer");
        jdbcTemplate.update("DELETE FROM tb_bot_policy_doc");
        jdbcTemplate.update("DELETE FROM tb_outbox_event");
        jdbcTemplate.update("DELETE FROM admin_audit_log");
        jdbcTemplate.update("DELETE FROM tb_user");

        LocalDateTime now = LocalDateTime.now();
        jdbcTemplate.update(
                """
                INSERT INTO tb_user
                    (id, openid, nick_name, avatar_url, auth_status,
                     create_time, update_time, is_deleted, is_admin, account_status)
                VALUES
                    (?, ?, ?, ?, 2, ?, ?, 0, 0, 0),
                    (?, ?, ?, ?, 0, ?, ?, 0, 0, 0),
                    (?, ?, ?, ?, 0, ?, ?, 0, 0, 0),
                    (?, ?, ?, ?, 0, ?, ?, 0, 0, 0)
                """,
                AUTHOR_ID, "test_openid_2", "作者A", "https://example.test/author.png", now, now,
                VIEWER_ID, "test_openid_123456", "访问者", "https://example.test/viewer.png", now, now,
                SECOND_VIEWER_ID, "runtime_openid_202", "第二访问者", "https://example.test/viewer-2.png", now, now,
                BOT_ID, "runtime_bot", "运行时机器人", null, now, now
        );
        jdbcTemplate.update(
                """
                INSERT INTO tb_user_auth
                    (user_id, identity_type, real_name, school_id, quanta_batch,
                     quanta_department, audit_status, create_time, update_time)
                VALUES (?, 1, '作者', 'runtime-author', '2026', '工程组', 1, ?, ?)
                """,
                AUTHOR_ID, now, now
        );
        jdbcTemplate.update(
                """
                INSERT INTO tb_user_auth
                    (user_id, identity_type, real_name, school_id, quanta_batch,
                     quanta_department, audit_status, create_time, update_time)
                VALUES (?, 1, '待审用户', 'runtime-pending', '2026', '测试组', 0, ?, ?)
                """,
                SECOND_VIEWER_ID, now, now
        );
        jdbcTemplate.update(
                """
                INSERT INTO tb_content
                    (content_id, content_type, title, content, publish_user_id,
                     audit_status, liked, comment_count, collect_count,
                     create_time, update_time, is_deleted)
                VALUES (?, 1, '缓存验收详情', '真实 MySQL 详情正文', ?, 1, 0, 0, 0, ?, ?, 0)
                """,
                CONTENT_ID, AUTHOR_ID, now, now
        );
        jdbcTemplate.update(
                """
                INSERT INTO tb_content
                    (content_id, content_type, title, content, publish_user_id,
                     audit_status, liked, comment_count, collect_count,
                     create_time, update_time, is_deleted)
                VALUES (?, 2, '第二作者详情', '第二作者真实内容', ?, 1, 0, 0, 0, ?, ?, 0)
                """,
                SECOND_CONTENT_ID, BOT_ID, now, now
        );
        jdbcTemplate.update(
                "INSERT INTO tb_content_image(content_id, image_url, sort, create_time) VALUES (?, ?, 0, ?)",
                CONTENT_ID, "https://example.test/content.png", now
        );
        jdbcTemplate.update(
                "INSERT INTO tb_user_follow(user_id, follow_user_id, create_time, update_time, is_deleted) VALUES (?, ?, ?, ?, 0)",
                VIEWER_ID, AUTHOR_ID, now, now
        );
        jdbcTemplate.update(
                "INSERT INTO tb_user_follow(user_id, follow_user_id, create_time, update_time, is_deleted) VALUES (?, ?, ?, ?, 0)",
                VIEWER_ID, BOT_ID, now, now
        );
        jdbcTemplate.update(
                "INSERT INTO user_role(user_id, role_code, created_by) VALUES (?, 'BOT', ?)",
                BOT_ID, BOT_ID
        );
        jdbcTemplate.update(
                "INSERT INTO tb_content_like(content_id, user_id, create_time) VALUES (?, ?, ?)",
                CONTENT_ID, SECOND_VIEWER_ID, now
        );
        jdbcTemplate.update(
                "INSERT INTO tb_content_collect(content_id, user_id, create_time) VALUES (?, ?, ?)",
                CONTENT_ID, SECOND_VIEWER_ID, now
        );
        redisTemplate.opsForZSet().add(
                RedisConstants.FEED_ALL_KEY + VIEWER_ID,
                String.valueOf(CONTENT_ID),
                System.currentTimeMillis() - 1_000L
        );
        redisTemplate.opsForZSet().add(
                RedisConstants.FEED_ALL_KEY + VIEWER_ID,
                String.valueOf(SECOND_CONTENT_ID),
                System.currentTimeMillis() - 500L
        );
    }

    @AfterEach
    void clearGovernanceThreadState() {
        SecurityContextHolder.clearContext();
        BaseContext.removeCurrentId();
    }

    @Test
    void httpUsesRealJwtSessionSnapshotAndRejectsReplacementAndBan() {
        String firstToken = login("test");
        assertThat(redisTemplate.opsForValue().get(
                RedisConstants.LOGIN_USER_KEY + VIEWER_ID)).isEqualTo(firstToken);

        ResponseEntity<String> firstInfo = get("/user/info", firstToken);
        assertThat(firstInfo.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(json(firstInfo).get("code")).isEqualTo(200);

        sleepForTokenClockTick();
        String replacementToken = login("test");
        assertThat(replacementToken).isNotEqualTo(firstToken);
        assertThat(get("/user/info", firstToken).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(get("/user/info", replacementToken).getStatusCode())
                .isEqualTo(HttpStatus.OK);

        redisTemplate.opsForValue().set(
                RedisConstants.USER_BANNED_KEY + VIEWER_ID, "1");
        assertThat(get("/user/info", replacementToken).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);

        redisTemplate.delete(RedisConstants.USER_BANNED_KEY + VIEWER_ID);
        assertThat(exchange(HttpMethod.POST, "/user/logout", replacementToken, null)
                .getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(get("/user/info", replacementToken).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);

        String expiredToken = JwtUtil.createJWT(
                TEST_SECRET,
                -1L,
                Map.of(JwtClaimsConstant.USER_ID, VIEWER_ID));
        assertThat(get("/user/info", expiredToken).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void serviceTokenUsesTheSameProductionHttpAuthenticationBoundary() {
        String regularBotToken = normalToken(BOT_ID);
        redisTemplate.opsForValue().set(
                RedisConstants.LOGIN_USER_KEY + BOT_ID, regularBotToken);
        assertThat(get(
                "/bot/content/sync?since=2000-01-01&pageSize=10", regularBotToken)
                .getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        redisTemplate.delete(RedisConstants.LOGIN_USER_KEY + BOT_ID);

        Map<String, Object> claims = new HashMap<>();
        claims.put(JwtClaimsConstant.USER_ID, BOT_ID);
        claims.put(JwtClaimsConstant.TOKEN_TYPE, JwtClaimsConstant.SERVICE_TOKEN_TYPE);
        String serviceToken = JwtUtil.createJWT(TEST_SECRET, 3_600_000L, claims);

        ResponseEntity<String> response = get(
                "/bot/content/sync?since=2000-01-01&pageSize=10", serviceToken);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(json(response).get("code")).isEqualTo(200);
        assertThat(redisTemplate.opsForValue().get(
                RedisConstants.LOGIN_USER_KEY + BOT_ID)).isNull();

        String wrongUserServiceToken = serviceToken(VIEWER_ID);
        assertThat(get(
                "/bot/content/sync?since=2000-01-01&pageSize=10",
                wrongUserServiceToken).getStatusCode())
                .as("service identity for a non-bot user must fail closed")
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void detailUsesL1L2AndRealAuthorAndBrowseHistoryPaths() {
        String viewerToken = login("test");

        ResponseEntity<String> first = get(
                "/content/detail/" + CONTENT_ID, viewerToken);
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> firstData = data(first);
        assertThat(firstData.get("title")).isEqualTo("缓存验收详情");
        assertThat(firstData.get("nickName")).isEqualTo("作者A");
        assertThat(firstData.get("isLiked")).isEqualTo(false);
        assertThat(redisTemplate.hasKey(
                RedisConstants.CONTENT_DETAIL_KEY + CONTENT_ID)).isTrue();
        assertThat(countBrowseHistory(VIEWER_ID)).isEqualTo(1);

        ResponseEntity<String> second = get(
                "/content/detail/" + CONTENT_ID, viewerToken);
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(data(second).get("title")).isEqualTo("缓存验收详情");
        assertThat(countBrowseHistory(VIEWER_ID)).isEqualTo(1);

        jdbcTemplate.update(
                "UPDATE tb_browse_history SET create_time = ?, update_time = ? "
                        + "WHERE user_id = ? AND content_id = ?",
                LocalDateTime.of(2000, 1, 1, 0, 0),
                LocalDateTime.of(2000, 1, 1, 0, 0),
                VIEWER_ID,
                CONTENT_ID
        );
        assertThat(get("/content/detail/" + CONTENT_ID, viewerToken)
                .getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(browseUpdatedAt(VIEWER_ID))
                .as("a cache hit still performs the per-viewer browse side effect")
                .isAfter(LocalDateTime.of(2000, 1, 1, 0, 0));

        String secondViewerToken = normalToken(SECOND_VIEWER_ID);
        ResponseEntity<String> secondViewer = get(
                "/content/detail/" + CONTENT_ID, secondViewerToken);
        assertThat(secondViewer.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(data(secondViewer).get("isLiked")).isEqualTo(true);
        assertThat(data(secondViewer).get("isCollected")).isEqualTo(true);
        assertThat(countBrowseHistory(SECOND_VIEWER_ID)).isEqualTo(1);
    }

    @Test
    void authorWriteEvictsProfileCacheAndFeedUsesBatchAuthorRead() {
        String viewerToken = login("test");
        ResponseEntity<String> firstFeed = get(
                "/follow/feed?pageSize=10", viewerToken);
        assertThat(firstFeed.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(firstFeed.getBody()).contains("作者A");
        assertThat(redisTemplate.opsForZSet().score(
                RedisConstants.FEED_ALL_KEY + VIEWER_ID,
                String.valueOf(CONTENT_ID))).isNotNull();

        String authorToken = login("test2");
        ResponseEntity<String> update = exchange(
                HttpMethod.PUT,
                "/user/info/update",
                authorToken,
                Map.of("nickName", "作者B"));
        assertThat(update.getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<String> secondFeed = get(
                "/follow/feed?pageSize=10", viewerToken);
        assertThat(secondFeed.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(secondFeed.getBody()).contains("作者B");
    }

    @Test
    void httpLikeWriteCommitsMySqlOutboxAndEvictsDetailCacheAfterCommit() {
        String viewerToken = login("test");
        assertThat(data(get("/content/detail/" + CONTENT_ID, viewerToken))
                .get("isLiked")).isEqualTo(false);
        String key = RedisConstants.CONTENT_DETAIL_KEY + CONTENT_ID;

        ResponseEntity<String> like = exchange(
                HttpMethod.POST,
                "/content/like/" + CONTENT_ID,
                viewerToken,
                Map.of("liked", true));
        assertThat(like.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(json(like).get("code")).isEqualTo(200);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM tb_content_like WHERE content_id = ? AND user_id = ?",
                Integer.class,
                CONTENT_ID,
                VIEWER_ID)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM tb_outbox_event WHERE aggregate_id = ?",
                Integer.class,
                CONTENT_ID)).isGreaterThan(0);
        assertThat(redisTemplate.opsForZSet().score(
                RedisConstants.CONTENT_LIKED_KEY + CONTENT_ID,
                String.valueOf(VIEWER_ID))).isNotNull();
        assertThat(redisTemplate.opsForValue().get(key))
                .startsWith("__INVALIDATED__:");

        ResponseEntity<String> afterWrite = get(
                "/content/detail/" + CONTENT_ID, viewerToken);
        assertThat(afterWrite.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(data(afterWrite).get("isLiked")).isEqualTo(true);
        assertThat(data(afterWrite).get("liked")).isEqualTo(1);
    }

    @Test
    void detailInvalidationRunsAfterCommitButNotAfterRollback() {
        String viewerToken = login("test");
        assertThat(get("/content/detail/" + CONTENT_ID, viewerToken)
                .getStatusCode()).isEqualTo(HttpStatus.OK);
        String key = RedisConstants.CONTENT_DETAIL_KEY + CONTENT_ID;
        String original = redisTemplate.opsForValue().get(key);
        assertThat(original).isNotNull();

        jdbcTemplate.update(
                "UPDATE tb_browse_history SET create_time = ?, update_time = ? WHERE user_id = ? AND content_id = ?",
                LocalDateTime.of(2000, 1, 1, 0, 0),
                LocalDateTime.of(2000, 1, 1, 0, 0),
                VIEWER_ID,
                CONTENT_ID
        );
        assertThat(browseUpdatedAt(VIEWER_ID))
                .isEqualTo(LocalDateTime.of(2000, 1, 1, 0, 0));
        assertThat(get("/content/detail/" + CONTENT_ID, viewerToken)
                .getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(browseUpdatedAt(VIEWER_ID))
                .isAfter(LocalDateTime.of(2000, 1, 1, 0, 0));

        new TransactionTemplate(transactionManager).execute(status -> {
            jdbcTemplate.update(
                    "UPDATE tb_content SET title = ?, update_time = ? WHERE content_id = ?",
                    "提交后标题",
                    LocalDateTime.now(),
                    CONTENT_ID
            );
            contentDetailCacheInvalidator.evictAfterCommit(CONTENT_ID, "runtime-commit");
            return null;
        });
        assertThat(redisTemplate.opsForValue().get(key))
                .startsWith("__INVALIDATED__:");

        ResponseEntity<String> committed = get(
                "/content/detail/" + CONTENT_ID, viewerToken);
        assertThat(committed.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(data(committed).get("title")).isEqualTo("提交后标题");
        String refilled = redisTemplate.opsForValue().get(key);
        assertThat(refilled).isNotNull().isNotEqualTo(original);

        new TransactionTemplate(transactionManager).execute(status -> {
            jdbcTemplate.update(
                    "UPDATE tb_content SET title = ?, update_time = ? WHERE content_id = ?",
                    "回滚标题",
                    LocalDateTime.now(),
                    CONTENT_ID
            );
            contentDetailCacheInvalidator.evictAfterCommit(CONTENT_ID, "runtime-rollback");
            status.setRollbackOnly();
            return null;
        });
        assertThat(jdbcTemplate.queryForObject(
                "SELECT title FROM tb_content WHERE content_id = ?",
                String.class,
                CONTENT_ID)).isEqualTo("提交后标题");
        assertThat(redisTemplate.opsForValue().get(key)).isEqualTo(refilled);
        assertThat(data(get("/content/detail/" + CONTENT_ID, viewerToken))
                .get("title")).isEqualTo("提交后标题");
    }

    @Test
    void stompConnectUsesTheSameRealTokenAuthenticationService() throws Exception {
        String token = login("test");
        assertStompConnectAccepted(token);
        assertStompConnectAccepted(serviceToken(BOT_ID));

        String expiredToken = JwtUtil.createJWT(
                TEST_SECRET,
                -1L,
                Map.of(JwtClaimsConstant.USER_ID, VIEWER_ID));
        assertStompConnectRejected(expiredToken);

        String bannedToken = login("test");
        redisTemplate.opsForValue().set(
                RedisConstants.USER_BANNED_KEY + VIEWER_ID, "1");
        assertStompConnectRejected(bannedToken);
        redisTemplate.delete(RedisConstants.USER_BANNED_KEY + VIEWER_ID);

        assertStompConnectRejected(serviceToken(VIEWER_ID));
    }

    @Test
    void realMapperCountersProveAuthFeedAndDetailCacheBoundaries() {
        org.mockito.Mockito.clearInvocations(
                userMapperSpy,
                identityMapperSpy,
                userRoleMapperSpy,
                contentMapperSpy
        );

        String viewerToken = login("test");
        assertThat(get("/user/security-context", viewerToken).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        org.mockito.Mockito.verify(userRoleMapperSpy, org.mockito.Mockito.times(1))
                .findRoleCodesByUserId(VIEWER_ID);
        org.mockito.Mockito.verify(userMapperSpy, org.mockito.Mockito.times(1))
                .getById(VIEWER_ID);
        org.mockito.Mockito.verify(identityMapperSpy, org.mockito.Mockito.times(1))
                .getUserAuthByUserId(VIEWER_ID);

        org.mockito.Mockito.clearInvocations(
                userMapperSpy, identityMapperSpy, userRoleMapperSpy);
        assertThat(get("/user/security-context", viewerToken).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        org.mockito.Mockito.verify(userMapperSpy, org.mockito.Mockito.never())
                .getById(VIEWER_ID);
        org.mockito.Mockito.verify(identityMapperSpy, org.mockito.Mockito.never())
                .getUserAuthByUserId(VIEWER_ID);
        org.mockito.Mockito.verify(userRoleMapperSpy, org.mockito.Mockito.never())
                .findRoleCodesByUserId(VIEWER_ID);

        org.mockito.Mockito.clearInvocations(userMapperSpy);
        assertThat(get("/follow/feed?pageSize=10", viewerToken)
                .getStatusCode()).isEqualTo(HttpStatus.OK);
        org.mockito.Mockito.clearInvocations(userMapperSpy);
        assertThat(get("/follow/feed?pageSize=10", viewerToken)
                .getStatusCode()).isEqualTo(HttpStatus.OK);
        org.mockito.Mockito.verify(userMapperSpy, org.mockito.Mockito.never())
                .selectUserAuthInfoByIds(org.mockito.ArgumentMatchers.anyList());

        authorProfileCache.evict(AUTHOR_ID);
        org.mockito.Mockito.clearInvocations(userMapperSpy);
        assertThat(get("/follow/feed?pageSize=10", viewerToken)
                .getStatusCode()).isEqualTo(HttpStatus.OK);
        org.mockito.Mockito.verify(userMapperSpy, org.mockito.Mockito.times(1))
                .selectUserAuthInfoByIds(org.mockito.ArgumentMatchers.argThat(
                        ids -> ids.size() == 1 && ids.contains(AUTHOR_ID)));
        org.mockito.Mockito.verify(userMapperSpy, org.mockito.Mockito.never())
                .selectUserAuthInfoById(org.mockito.ArgumentMatchers.anyLong());

        org.mockito.Mockito.clearInvocations(contentMapperSpy);
        assertThat(get("/content/detail/" + CONTENT_ID, viewerToken)
                .getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(get("/content/detail/" + CONTENT_ID, viewerToken)
                .getStatusCode()).isEqualTo(HttpStatus.OK);
        org.mockito.Mockito.verify(contentMapperSpy, org.mockito.Mockito.times(1))
                .selectById(CONTENT_ID);

        java.util.concurrent.atomic.AtomicBoolean l2LoaderCalled =
                new java.util.concurrent.atomic.AtomicBoolean();
        ContentDetailCacheService independentL2 = new ContentDetailCacheServiceImpl(
                redisTemplate,
                objectMapper,
                readPathCacheProperties
        );
        ContentDetailCacheEntry l2Hit = independentL2.getOrLoad(
                CONTENT_ID,
                () -> {
                    l2LoaderCalled.set(true);
                    throw new AssertionError("L2 hit unexpectedly invoked loader");
                }
        );
        assertThat(l2Hit).isNotNull();
        assertThat(l2LoaderCalled).as("independent cache instance must read Redis L2")
                .isFalse();
    }

    @Test
    void httpAuthenticationFailsClosedDuringRedisPauseAndRecovers() {
        String token = login("test");
        assertThat(get("/user/info", token).getStatusCode())
                .isEqualTo(HttpStatus.OK);

        String containerId = REDIS.getContainerId();
        REDIS.getDockerClient().pauseContainerCmd(containerId).exec();
        boolean paused = true;
        try {
            assertThat(get("/user/info", token).getStatusCode())
                    .as("Redis authentication outage must not bypass HTTP security")
                    .isEqualTo(HttpStatus.UNAUTHORIZED);
        } finally {
            if (paused) {
                REDIS.getDockerClient().unpauseContainerCmd(containerId).exec();
            }
        }

        awaitRedisAvailable();
        assertThat(get("/user/info", token).getStatusCode())
                .as("the same isolated Redis session must recover after unpause")
                .isEqualTo(HttpStatus.OK);
    }

    @Test
    void productionDetailCacheFallsBackToRealMySqlAndKeepsL1DuringRedisPause() {
        contentDetailCacheService.evict(CONTENT_ID);
        String detailKey = RedisConstants.CONTENT_DETAIL_KEY + CONTENT_ID;
        String tombstoneBeforePause = redisTemplate.opsForValue().get(detailKey);
        assertThat(tombstoneBeforePause)
                .as("pre-existing eviction must leave a Redis tombstone")
                .isNotNull()
                .startsWith("__INVALIDATED__:");
        org.mockito.Mockito.clearInvocations(contentMapperSpy);

        String containerId = REDIS.getContainerId();
        REDIS.getDockerClient().pauseContainerCmd(containerId).exec();
        boolean paused = true;
        try {
            ContentDetailCacheEntry mysqlFallback = contentDetailCacheService.getOrLoad(
                    CONTENT_ID,
                    () -> contentDetailDataLoader.load(CONTENT_ID)
            );
            assertThat(mysqlFallback.snapshot()).isNotNull();
            assertThat(mysqlFallback.snapshot().title()).isEqualTo("缓存验收详情");
            org.mockito.Mockito.verify(contentMapperSpy, org.mockito.Mockito.times(1))
                    .selectById(CONTENT_ID);

            ContentDetailCacheEntry l1Hit = contentDetailCacheService.getOrLoad(
                    CONTENT_ID,
                    () -> {
                        throw new AssertionError("L1 hit unexpectedly invoked MySQL loader");
                    }
            );
            assertThat(l1Hit).isEqualTo(mysqlFallback);
        } finally {
            if (paused) {
                REDIS.getDockerClient().unpauseContainerCmd(containerId).exec();
            }
        }

        awaitRedisAvailable();
        assertThat(redisTemplate.opsForValue().get(detailKey))
                .as("a Redis outage fallback must not overwrite the L2 tombstone")
                .isEqualTo(tombstoneBeforePause);
        contentDetailCacheService.evict(CONTENT_ID);
        ContentDetailCacheEntry recovered = contentDetailCacheService.getOrLoad(
                CONTENT_ID,
                () -> contentDetailDataLoader.load(CONTENT_ID)
        );
        assertThat(recovered.snapshot()).isNotNull();
        String recoveredRaw = redisTemplate.opsForValue().get(detailKey);
        assertThat(recoveredRaw).isNotNull()
                .doesNotStartWith("__INVALIDATED__:");
    }

    @Test
    void productionGovernanceServicesCommitAndRollbackRealUserReadCaches() {
        installAdminActor();

        AuthenticationSnapshot initialAuthor =
                authenticationSnapshotCache.get(AUTHOR_ID, false);
        assertThat(initialAuthor.roles())
                .doesNotContain(RoleConstants.CONTENT_AUDITOR);

        String authorToken = normalToken(AUTHOR_ID);
        adminRoleService.grantRole(AUTHOR_ID, RoleConstants.CONTENT_AUDITOR);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM user_role WHERE user_id = ? AND role_code = ?",
                Integer.class,
                AUTHOR_ID,
                RoleConstants.CONTENT_AUDITOR)).isEqualTo(1);
        assertThat(redisTemplate.opsForValue().get(
                RedisConstants.LOGIN_USER_KEY + AUTHOR_ID)).isNull();
        assertThat(authenticationSnapshotCache.get(AUTHOR_ID, false).roles())
                .contains(RoleConstants.CONTENT_AUDITOR);

        String rollbackRoleToken = normalToken(AUTHOR_ID);
        AuthenticationSnapshot beforeRoleRollback =
                authenticationSnapshotCache.get(AUTHOR_ID, false);
        new TransactionTemplate(transactionManager).execute(status -> {
            adminRoleService.grantRole(AUTHOR_ID, RoleConstants.OPERATIONS_ADMIN);
            status.setRollbackOnly();
            return null;
        });
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM user_role WHERE user_id = ? AND role_code = ?",
                Integer.class,
                AUTHOR_ID,
                RoleConstants.OPERATIONS_ADMIN)).isZero();
        assertThat(redisTemplate.opsForValue().get(
                RedisConstants.LOGIN_USER_KEY + AUTHOR_ID))
                .isEqualTo(rollbackRoleToken);
        AuthenticationSnapshot afterRoleRollback =
                authenticationSnapshotCache.get(AUTHOR_ID, false);
        assertThat(afterRoleRollback)
                .isSameAs(beforeRoleRollback);
        assertThat(afterRoleRollback.roles())
                .doesNotContain(RoleConstants.OPERATIONS_ADMIN);

        adminRoleService.revokeRole(AUTHOR_ID, RoleConstants.CONTENT_AUDITOR);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM user_role WHERE user_id = ? AND role_code = ?",
                Integer.class,
                AUTHOR_ID,
                RoleConstants.CONTENT_AUDITOR)).isZero();

        Long pendingAuthId = jdbcTemplate.queryForObject(
                "SELECT auth_id FROM tb_user_auth WHERE user_id = ?",
                Long.class,
                SECOND_VIEWER_ID);
        AuthenticationSnapshot pending =
                authenticationSnapshotCache.get(SECOND_VIEWER_ID, false);
        assertThat(pending.verified()).isFalse();
        int outboxBeforeApproval = outboxCountForAggregate(pendingAuthId);

        identityExamService.audit(IdentityAuditDTO.builder()
                .authId(pendingAuthId)
                .auditResult(AuditStatus.APPROVED.getCode())
                .build());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT audit_status FROM tb_user_auth WHERE auth_id = ?",
                Integer.class,
                pendingAuthId)).isEqualTo(AuditStatus.APPROVED.getCode());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT auth_status FROM tb_user WHERE id = ?",
                Integer.class,
                SECOND_VIEWER_ID)).isEqualTo(2);
        assertThat(outboxCountForAggregate(pendingAuthId))
                .isGreaterThan(outboxBeforeApproval);
        assertThat(authenticationSnapshotCache.get(SECOND_VIEWER_ID, false).verified())
                .isTrue();

        jdbcTemplate.update(
                "UPDATE tb_user_auth SET audit_status = 0, audit_remark = NULL, "
                        + "audit_time = NULL WHERE auth_id = ?",
                pendingAuthId);
        jdbcTemplate.update(
                "UPDATE tb_user SET auth_status = 0 WHERE id = ?",
                SECOND_VIEWER_ID);
        authenticationSnapshotCache.evict(SECOND_VIEWER_ID);
        AuthenticationSnapshot pendingBeforeRollback =
                authenticationSnapshotCache.get(SECOND_VIEWER_ID, false);
        assertThat(pendingBeforeRollback.verified())
                .isFalse();
        int outboxBeforeRollback = outboxCountForAggregate(pendingAuthId);

        new TransactionTemplate(transactionManager).execute(status -> {
            identityExamService.audit(IdentityAuditDTO.builder()
                    .authId(pendingAuthId)
                    .auditResult(AuditStatus.REJECTED.getCode())
                    .auditRemark("runtime rollback")
                    .build());
            status.setRollbackOnly();
            return null;
        });
        assertThat(jdbcTemplate.queryForObject(
                "SELECT audit_status FROM tb_user_auth WHERE auth_id = ?",
                Integer.class,
                pendingAuthId)).isEqualTo(AuditStatus.PENDING.getCode());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT auth_status FROM tb_user WHERE id = ?",
                Integer.class,
                SECOND_VIEWER_ID)).isZero();
        assertThat(outboxCountForAggregate(pendingAuthId))
                .isEqualTo(outboxBeforeRollback);
        assertThat(authenticationSnapshotCache.get(SECOND_VIEWER_ID, false))
                .isSameAs(pendingBeforeRollback);
        assertThat(authenticationSnapshotCache.get(SECOND_VIEWER_ID, false).verified())
                .isFalse();

        String viewerToken = normalToken(VIEWER_ID);
        authenticationSnapshotCache.get(VIEWER_ID, false);
        adminUserService.banUser(VIEWER_ID);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT account_status FROM tb_user WHERE id = ?",
                Integer.class,
                VIEWER_ID)).isEqualTo(1);
        assertThat(redisTemplate.opsForValue().get(
                RedisConstants.USER_BANNED_KEY + VIEWER_ID)).isEqualTo("1");
        assertThat(redisTemplate.opsForValue().get(
                RedisConstants.LOGIN_USER_KEY + VIEWER_ID)).isNull();
        assertThat(authenticationSnapshotCache.get(VIEWER_ID, false).accountStatus())
                .isEqualTo(1);

        adminUserService.unbanUser(VIEWER_ID);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT account_status FROM tb_user WHERE id = ?",
                Integer.class,
                VIEWER_ID)).isZero();
        assertThat(redisTemplate.hasKey(
                RedisConstants.USER_BANNED_KEY + VIEWER_ID)).isFalse();
        assertThat(authenticationSnapshotCache.get(VIEWER_ID, false).accountStatus())
                .isZero();
        assertThat(get("/user/info", viewerToken).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        String restoredViewerToken = normalToken(VIEWER_ID);
        assertThat(get("/user/info", restoredViewerToken).getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }

    private String login(String code) {
        ResponseEntity<String> response = exchange(
                HttpMethod.POST,
                "/user/login",
                null,
                Map.of("code", code));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> body = json(response);
        assertThat(body.get("code")).isEqualTo(200);
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) body.get("data");
        assertThat(data).containsKey("token");
        return (String) data.get("token");
    }

    private void installAdminActor() {
        AuthenticatedUser actor = AuthenticatedUser.builder()
                .userId(VIEWER_ID)
                .roles(Set.of(RoleConstants.SUPER_ADMIN))
                .authorities(Set.of())
                .accountStatus(0)
                .verified(true)
                .admin(true)
                .build();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(actor, null, Set.of())
        );
        BaseContext.setCurrentId(VIEWER_ID);
    }

    private int outboxCountForAggregate(Long aggregateId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM tb_outbox_event WHERE aggregate_type = 'USER_AUTH' "
                        + "AND aggregate_id = ?",
                Integer.class,
                aggregateId
        );
        return count == null ? 0 : count;
    }

    private ResponseEntity<String> get(String path, String token) {
        return exchange(HttpMethod.GET, path, token, null);
    }

    private String normalToken(long userId) {
        String token = JwtUtil.createJWT(
                TEST_SECRET,
                3_600_000L,
                Map.of(JwtClaimsConstant.USER_ID, userId));
        redisTemplate.opsForValue().set(
                RedisConstants.LOGIN_USER_KEY + userId, token);
        return token;
    }

    private String serviceToken(long userId) {
        Map<String, Object> claims = new HashMap<>();
        claims.put(JwtClaimsConstant.USER_ID, userId);
        claims.put(JwtClaimsConstant.TOKEN_TYPE,
                JwtClaimsConstant.SERVICE_TOKEN_TYPE);
        return JwtUtil.createJWT(TEST_SECRET, 3_600_000L, claims);
    }

    private void assertStompConnectAccepted(String token) throws Exception {
        WebSocketStompClient stompClient = new WebSocketStompClient(
                new StandardWebSocketClient());
        stompClient.setMessageConverter(new StringMessageConverter());
        try {
            StompHeaders connectHeaders = new StompHeaders();
            connectHeaders.add("authorization", token);
            StompSession session = stompClient.connectAsync(
                            URI.create("ws://127.0.0.1:" + port + "/ws"),
                            new WebSocketHttpHeaders(),
                            connectHeaders,
                            new StompSessionHandlerAdapter() {})
                    .get(15, TimeUnit.SECONDS);
            assertThat(session.isConnected()).isTrue();
            session.disconnect();
        } finally {
            stompClient.stop();
        }
    }

    private void assertStompConnectRejected(String token) {
        WebSocketStompClient stompClient = new WebSocketStompClient(
                new StandardWebSocketClient());
        stompClient.setMessageConverter(new StringMessageConverter());
        CompletableFuture<StompSession> attempt = null;
        try {
            StompHeaders connectHeaders = new StompHeaders();
            connectHeaders.add("authorization", token);
            attempt = stompClient.connectAsync(
                    URI.create("ws://127.0.0.1:" + port + "/ws"),
                    new WebSocketHttpHeaders(),
                    connectHeaders,
                    new StompSessionHandlerAdapter() {});
            CompletableFuture<StompSession> connection = attempt;
            assertThatThrownByWithBoundedWait(connection);
        } finally {
            if (attempt != null) {
                attempt.cancel(true);
            }
            stompClient.stop();
        }
    }

    private void assertThatThrownByWithBoundedWait(
            CompletableFuture<StompSession> connection
    ) {
        try {
            StompSession session = connection.get(3, TimeUnit.SECONDS);
            throw new AssertionError(
                    "invalid STOMP token unexpectedly connected: "
                            + session.getSessionId()
            );
        } catch (ExecutionException | TimeoutException | CancellationException expected) {
            // A rejected CONNECT may complete exceptionally or remain uncompleted
            // after the production interceptor returns null; both are bounded rejects.
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted while awaiting STOMP rejection", interrupted);
        }
    }

    private ResponseEntity<String> exchange(
            HttpMethod method,
            String path,
            String token,
            Object body
    ) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (token != null) {
            headers.set("authorization", token);
        }
        return restTemplate.exchange(
                path,
                method,
                new HttpEntity<>(body, headers),
                String.class
        );
    }

    private Map<String, Object> json(ResponseEntity<String> response) {
        try {
            return objectMapper.readValue(
                    response.getBody(),
                    new TypeReference<>() { }
            );
        } catch (Exception exception) {
            throw new AssertionError("response was not JSON", exception);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> data(ResponseEntity<String> response) {
        return (Map<String, Object>) json(response).get("data");
    }

    private int countBrowseHistory(long userId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM tb_browse_history WHERE user_id = ? AND content_id = ? AND is_deleted = 0",
                Integer.class,
                userId,
                CONTENT_ID
        );
        return count == null ? 0 : count;
    }

    private LocalDateTime browseUpdatedAt(long userId) {
        return jdbcTemplate.queryForObject(
                "SELECT update_time FROM tb_browse_history WHERE user_id = ? AND content_id = ?",
                LocalDateTime.class,
                userId,
                CONTENT_ID
        );
    }

    private void clearRedis() {
        redisTemplate.getConnectionFactory().getConnection().serverCommands().flushDb();
    }

    private void awaitRedisAvailable() {
        AssertionError lastFailure = null;
        for (int attempt = 0; attempt < 20; attempt++) {
            try {
                redisTemplate.opsForValue().set(
                        "runtime:redis-recovery-probe", "ok", 2, TimeUnit.SECONDS);
                if ("ok".equals(redisTemplate.opsForValue()
                        .get("runtime:redis-recovery-probe"))) {
                    redisTemplate.delete("runtime:redis-recovery-probe");
                    return;
                }
            } catch (Exception exception) {
                lastFailure = new AssertionError(
                        "isolated Redis did not recover yet", exception);
            }
            try {
                Thread.sleep(100L);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new AssertionError("interrupted while waiting for Redis recovery", interrupted);
            }
        }
        if (lastFailure != null) {
            throw lastFailure;
        }
        throw new AssertionError("isolated Redis did not recover within 2 seconds");
    }

    private void sleepForTokenClockTick() {
        try {
            Thread.sleep(1_100L);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted while separating JWT issue times", exception);
        }
    }
}
