package com.quanta.demo0.platform.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.quanta.demo0.platform.security.service.AuthenticationSnapshotCache;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** 管理密码登录真栈验收：真实 HTTP/JWT/MyBatis/MySQL/Redis，隔离用户与随机测试密码。 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.rabbitmq.listener.simple.auto-startup=false", "spring.task.scheduling.enabled=false",
        "quanta.recommend.warmup-on-startup=false", "rag.enabled=false", "rag.ai-enabled=false",
        "spring.sql.init.mode=never", "quanta.security.dev-login-enabled=false",
        "quanta.jwt.user-secret-key=isolated-admin-login-test-secret-0123456789",
        "quanta.jwt.user-ttl=3600000", "quanta.jwt.user-token-name=authorization",
        "quanta.wechat.appid=runtime-test", "quanta.wechat.secret=runtime-test",
        "spring.elasticsearch.host=127.0.0.1", "spring.elasticsearch.port=1", "spring.elasticsearch.scheme=http"
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class AdminPasswordLoginIntegrationTests {
    @Container static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0.43")
            .withDatabaseName("demo").withUsername("demo").withPassword("demo")
            .withInitScript("db/read-path-cache-runtime-schema.sql");
    @Container static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7.2-alpine").withExposedPorts(6379);
    @Container static final RabbitMQContainer RABBIT = new RabbitMQContainer("rabbitmq:3.13-management-alpine")
            .withAdminUser("runtime").withAdminPassword("runtime");

    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", REDIS::getFirstMappedPort);
        registry.add("spring.data.redis.database", () -> 0);
        registry.add("spring.rabbitmq.host", RABBIT::getHost);
        registry.add("spring.rabbitmq.port", RABBIT::getAmqpPort);
        registry.add("spring.rabbitmq.username", () -> "runtime");
        registry.add("spring.rabbitmq.password", () -> "runtime");
    }

    @Autowired TestRestTemplate http;
    @Autowired JdbcTemplate jdbc;
    @Autowired StringRedisTemplate redis;
    @Autowired ObjectMapper json;
    @Autowired org.springframework.context.ApplicationContext context;
    @Autowired AuthenticationSnapshotCache cache;
    @MockitoBean com.quanta.demo0.feed.config.RecommendFeedInitializer recommendFeedInitializer;
    @MockitoBean com.quanta.demo0.search.es.initializer.ElasticsearchIndexInitializer elasticsearchIndexInitializer;
    @MockitoBean com.quanta.demo0.rag.vector.VectorStoreInitializer vectorStoreInitializer;
    private String password;

    @BeforeEach void seed() throws Exception {
        // 使用生产迁移重复初始化，验证实际凭据表及幂等建表。
        ClassPathResource migration = new ClassPathResource("db/V_admin_credentials.sql");
        assertThat(migration.exists()).isTrue();
        try (var connection = jdbc.getDataSource().getConnection()) {
            ScriptUtils.executeSqlScript(connection, migration);
            ScriptUtils.executeSqlScript(connection, migration);
        }
        try (var connection = redis.getConnectionFactory().getConnection()) {
            connection.serverCommands().flushDb();
        }
        cache.evict(7001L);
        jdbc.update("DELETE FROM admin_credential");
        jdbc.update("DELETE FROM tb_bot_policy_doc");
        jdbc.update("DELETE FROM admin_audit_log");
        jdbc.update("DELETE FROM user_role");
        jdbc.update("DELETE FROM tb_user");
        jdbc.update("INSERT INTO tb_user(id,openid,nick_name,account_status,is_deleted) VALUES(7001,'isolated-admin','管理验收',0,0)");
        jdbc.update("INSERT INTO user_role(user_id,role_code,created_by) VALUES(7001,'SUPER_ADMIN',7001),(7001,'OPERATIONS_ADMIN',7001)");
        password = UUID.randomUUID() + "!Aa";
        jdbc.update("INSERT INTO admin_credential(user_id,username,password_hash,enabled) VALUES(7001,'admin-proof',?,1)", new BCryptPasswordEncoder(12).encode(password));
    }

    @Test void passwordLoginLoadsRealPermissionsAndLogoutRevokesTheToken() throws Exception {
        ResponseEntity<String> response = login(password);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = json.readTree(response.getBody());
        assertThat(body.path("code").asInt()).isEqualTo(200);
        assertThat(body.path("data").path("id").asLong()).isEqualTo(7001);
        String token = body.path("data").path("token").asText();
        assertThat(token).isNotBlank();
        assertThat(response.getBody()).doesNotContain("password_hash", password);
        HttpHeaders headers = new HttpHeaders(); headers.set("authorization", token);
        ResponseEntity<String> context = http.exchange("/user/security-context", HttpMethod.GET, new HttpEntity<>(headers), String.class);
        assertThat(json.readTree(context.getBody()).path("data").path("authorities").toString()).contains("ROLE_MANAGE");
        ResponseEntity<String> logout = http.exchange("/user/logout", HttpMethod.POST, new HttpEntity<>(headers), String.class);
        assertThat(json.readTree(logout.getBody()).path("code").asInt()).isEqualTo(200);
        assertThat(http.exchange("/user/security-context", HttpMethod.GET, new HttpEntity<>(headers), String.class).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test void invalidCredentialAccountStatusAndRolesNeverIssueSessions() throws Exception {
        assertRejected(login(UUID.randomUUID().toString()));
        jdbc.update("UPDATE tb_user SET account_status=1 WHERE id=7001"); assertRejected(login(password));
        jdbc.update("UPDATE tb_user SET account_status=0,is_deleted=1 WHERE id=7001"); assertRejected(login(password));
        jdbc.update("UPDATE tb_user SET is_deleted=0 WHERE id=7001");
        jdbc.update("DELETE FROM user_role"); assertRejected(login(password));
        jdbc.update("INSERT INTO user_role(user_id,role_code) VALUES(7001,'BOT')"); assertRejected(login(password));
        assertThat(redis.opsForValue().get("login:token:7001")).isNull();
    }

    @Test void developmentTestCodeIsDisabledAndLoginAttemptsAreRateLimited() throws Exception {
        ResponseEntity<String> development = http.postForEntity("/user/login", Map.of("code", "test"), String.class);
        assertThat(development.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        for (int attempt = 0; attempt < 5; attempt++) assertRejected(login(UUID.randomUUID().toString()));
        ResponseEntity<String> limited = login(password);
        assertThat(limited.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(limited.getHeaders().getFirst("Retry-After")).isNotBlank();
    }

    @Test void strictAdminLogoutRevokesOnlyThePresentedSessionAndPreservesBanMarkers() throws Exception {
        JsonNode body = json.readTree(login(password).getBody());
        String token = body.path("data").path("token").asText();
        HttpHeaders headers = new HttpHeaders(); headers.set("authorization", token);
        ResponseEntity<String> response = http.exchange("/admin/auth/logout", HttpMethod.POST, new HttpEntity<>(headers), String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(json.readTree(response.getBody()).path("code").asInt()).isEqualTo(200);
        assertThat(redis.opsForValue().get("login:token:7001")).isNull();
        // 并发登录产生的新会话与封禁标记不应被旧令牌的登出请求删除。
        redis.opsForValue().set("login:token:7001", "newer-isolated-session");
        redis.opsForValue().set("user:banned:7001", "1");
        context.getBean(com.quanta.demo0.platform.security.service.SessionService.class).revokeSession(7001L, token);
        assertThat(redis.opsForValue().get("login:token:7001")).isEqualTo("newer-isolated-session");
        assertThat(redis.opsForValue().get("user:banned:7001")).isEqualTo("1");
    }

    private ResponseEntity<String> login(String suppliedPassword) {
        return http.postForEntity("/admin/auth/login", Map.of("username", "admin-proof", "password", suppliedPassword), String.class);
    }

    @Test void roleManagementPersistsAuditsAndInvalidatesTheAffectedUserSession() throws Exception {
        HttpHeaders admin = headersFor(login(password));
        assertThat(http.getForEntity("/admin/roles/user/7001", String.class).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(json.readTree(exchange("/admin/roles/user/7001", HttpMethod.GET, admin, null).getBody()).path("data").toString())
                .contains("SUPER_ADMIN", "OPERATIONS_ADMIN");
        jdbc.update("INSERT INTO tb_user(id,openid,nick_name,account_status,is_deleted) VALUES(7002,'isolated-operator','运营验收',0,0)");
        jdbc.update("INSERT INTO user_role(user_id,role_code) VALUES(7002,'OPERATIONS_ADMIN')");
        jdbc.update("INSERT INTO admin_credential(user_id,username,password_hash) SELECT 7002,'operator-proof',password_hash FROM admin_credential WHERE user_id=7001");
        HttpHeaders operator = headersFor(http.postForEntity("/admin/auth/login", Map.of("username", "operator-proof", "password", password), String.class));
        assertThat(exchange("/admin/roles/7001/CONTENT_AUDITOR", HttpMethod.POST, operator, null).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(json.readTree(exchange("/admin/roles/7002/CONTENT_AUDITOR", HttpMethod.POST, admin, null).getBody()).path("code").asInt()).isEqualTo(200);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM user_role WHERE user_id=7002 AND role_code='CONTENT_AUDITOR'", Integer.class)).isEqualTo(1);
        assertThat(exchange("/user/security-context", HttpMethod.GET, operator, null).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM admin_audit_log WHERE action='ROLE_GRANT' AND result_status='SUCCESS'", Integer.class)).isEqualTo(1);
        assertThat(json.readTree(exchange("/admin/roles/7002/CONTENT_AUDITOR", HttpMethod.DELETE, admin, null).getBody()).path("code").asInt()).isEqualTo(200);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM user_role WHERE user_id=7002 AND role_code='CONTENT_AUDITOR'", Integer.class)).isZero();
    }

    @Test void policyDocumentsEnforceOperationsRoleAndPersistDeletionAndRestoration() throws Exception {
        String path = "/admin/knowledge/policy-docs";
        assertThat(http.getForEntity(path + "/page", String.class).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        jdbc.update("DELETE FROM user_role WHERE role_code='OPERATIONS_ADMIN'");
        HttpHeaders admin = headersFor(login(password));
        assertThat(exchange(path + "/page", HttpMethod.GET, admin, null).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        jdbc.update("INSERT INTO user_role(user_id,role_code) VALUES(7001,'OPERATIONS_ADMIN')"); cache.evict(7001L);
        Map<String, String> document = Map.of("docId", "policy:admin-proof", "title", "管理端政策验收", "content", "隔离测试政策正文");
        assertThat(json.readTree(exchange(path, HttpMethod.POST, admin, document).getBody()).path("code").asInt()).isEqualTo(200);
        JsonNode page = json.readTree(exchange(path + "/page?keyword=管理端&status=ACTIVE", HttpMethod.GET, admin, null).getBody()).path("data");
        assertThat(page.path("total").asLong()).isEqualTo(1);
        assertThat(page.path("records").get(0).path("docId").asText()).isEqualTo("policy:admin-proof");
        assertThat(json.readTree(exchange(path + "/policy:admin-proof", HttpMethod.DELETE, admin, null).getBody()).path("code").asInt()).isEqualTo(200);
        assertThat(jdbc.queryForObject("SELECT is_deleted FROM tb_bot_policy_doc WHERE doc_id='policy:admin-proof'", Integer.class)).isEqualTo(1);
        assertThat(json.readTree(exchange(path + "/policy:admin-proof", HttpMethod.GET, admin, null).getBody()).path("data").path("isDeleted").asInt()).isEqualTo(1);
        var sync = context.getBean(com.quanta.demo0.content.service.BotContentSyncService.class).getSync("2000-01-01", 1, 100);
        assertThat(sync.getItems()).anySatisfy(doc -> {
            assertThat(doc.getDocId()).isEqualTo("policy:admin-proof");
            assertThat(doc.getStatus()).isEqualTo("deleted");
        });
        assertThat(json.readTree(exchange(path, HttpMethod.POST, admin, document).getBody()).path("code").asInt()).isEqualTo(200);
        assertThat(jdbc.queryForObject("SELECT is_deleted FROM tb_bot_policy_doc WHERE doc_id='policy:admin-proof'", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM admin_audit_log WHERE target_type='POLICY_DOC' AND result_status='SUCCESS'", Integer.class)).isEqualTo(3);
    }

    private HttpHeaders headersFor(ResponseEntity<String> response) throws Exception {
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        HttpHeaders headers = new HttpHeaders();
        headers.set("authorization", json.readTree(response.getBody()).path("data").path("token").asText());
        return headers;
    }

    private ResponseEntity<String> exchange(String path, HttpMethod method, HttpHeaders headers, Object body) {
        return http.exchange(path, method, new HttpEntity<>(body, headers), String.class);
    }

    private void assertRejected(ResponseEntity<String> response) throws Exception {
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(json.readTree(response.getBody()).path("data").path("token").asText()).isEmpty();
    }
}
