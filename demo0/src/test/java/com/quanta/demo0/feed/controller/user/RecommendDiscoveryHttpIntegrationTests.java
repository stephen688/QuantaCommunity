package com.quanta.demo0.feed.controller.user;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.quanta.demo0.content.controller.user.ContentController;
import com.quanta.demo0.content.mapper.ContentMapper;
import com.quanta.demo0.content.service.ContentQueryService;
import com.quanta.demo0.content.service.impl.ContentQueryServiceImpl;
import com.quanta.demo0.content.vo.ContentSnapshotVO;
import com.quanta.demo0.feed.properties.RecommendProperties;
import com.quanta.demo0.feed.service.HotContentService;
import com.quanta.demo0.feed.service.RecommendExposureService;
import com.quanta.demo0.feed.service.RecommendRerankService;
import com.quanta.demo0.feed.service.RecommendSessionService;
import com.quanta.demo0.feed.service.UserInterestProfileService;
import com.quanta.demo0.feed.service.impl.FeedQueryServiceImpl;
import com.quanta.demo0.feed.service.impl.RecommendDiscoverySelector;
import com.quanta.demo0.feed.service.impl.RecommendExposureServiceImpl;
import com.quanta.demo0.feed.service.impl.RecommendSessionServiceImpl;
import com.quanta.demo0.feed.service.impl.RecommendSessionStore;
import com.quanta.demo0.interaction.service.ContentInteractionService;
import com.quanta.demo0.platform.redis.constant.RedisConstants;
import com.quanta.demo0.platform.security.context.BaseContext;
import com.quanta.demo0.user.service.AuthorProfileCache;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.boot.test.autoconfigure.MybatisTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import org.mockito.Mockito;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.quanta.demo0.platform.redis.constant.RedisConstants.RECOMMEND_ALL_KEY;
import static com.quanta.demo0.platform.redis.constant.RedisConstants.RECOMMEND_HOT_ALL_KEY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 推荐发现 HTTP 边界集成测试。
 *
 * <p>控制器、会话编排、会话/曝光 Redis Lua 和内容 MyBatis 查询走真实实现；
 * 作者资料、互动状态与重排只保留 Feed 装配所需的最小测试替身，不启动完整 AI 应用。</p>
 */
@MybatisTest
@Testcontainers
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class RecommendDiscoveryHttpIntegrationTests {

    private static final String GUEST_A = "9845e25a-4e42-4f71-ab21-964ec8a68fb5";
    private static final String GUEST_B = "b1c2d3e4-f5a6-47b8-89c0-d1e2f3a4b5c6";
    private static final String GUEST_C = "c1d2e3f4-a5b6-47c8-89d0-e1f2a3b4c5d6";
    private static final String SESSION_A = "7fac5fa8-8b33-48ec-bd5b-6c2e8e762fc0";
    private static final String SESSION_B = "4b8e2d57-67dd-4e11-9c1a-68e3e7ab680a";
    private static final String SESSION_C = "f4f5b6c7-d8e9-4a01-b2c3-d4e5f6a7b8c9";
    private static final String SESSION_D = "d1e2f3a4-b5c6-47d8-89e0-f1a2b3c4d5e6";
    private static final String SESSION_E = "a1b2c3d4-e5f6-4789-90ab-c1d2e3f4a5b6";
    private static final LocalDateTime TIE_TIME = LocalDateTime.of(2026, 10, 4, 9, 0, 0, 123_000_000);

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0.43")
            .withDatabaseName("demo")
            .withUsername("demo")
            .withPassword("demo")
            .withInitScript("db/reliability-test-schema.sql");

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7.2-alpine"))
            .withExposedPorts(6379);

    @DynamicPropertySource
    static void registerDataSource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.datasource.driver-class-name", MYSQL::getDriverClassName);
        registry.add("mybatis.mapper-locations", () -> "classpath*:/mapper/**/*.xml");
        registry.add("mybatis.configuration.map-underscore-to-camel-case", () -> "true");
    }

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ContentMapper contentMapper;

    private final ObjectMapper json = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    private LettuceConnectionFactory redisConnectionFactory;
    private StringRedisTemplate redis;
    private RecommendProperties properties;
    private ContentQueryService contentQueryService;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        resetMySqlFacts();
        redisConnectionFactory = new LettuceConnectionFactory(REDIS.getHost(), REDIS.getMappedPort(6379));
        redisConnectionFactory.afterPropertiesSet();
        redis = new StringRedisTemplate(redisConnectionFactory);
        redis.afterPropertiesSet();
        deleteRedisKeys();
        seedRecommendZSets();

        properties = new RecommendProperties();
        properties.getDiscovery().setEnabled(true);
        properties.getDiscovery().setExplorationRatio(0.0D);
        properties.getDiscovery().setRecallWindowSteps(List.of(10));
        properties.getDiscovery().setScanBudget(20);
        properties.getDiscovery().setCandidateBatchSize(10);

        ContentQueryServiceImpl queryService = new ContentQueryServiceImpl();
        ReflectionTestUtils.setField(queryService, "contentMapper", contentMapper);
        contentQueryService = queryService;

        RecommendSessionStore sessionStore = new RecommendSessionStore(redis, json, properties);
        RecommendExposureService exposureService = new RecommendExposureServiceImpl(redis, sessionStore, properties);
        UserInterestProfileService profiles = Mockito.mock(UserInterestProfileService.class);
        RecommendDiscoverySelector selector = new RecommendDiscoverySelector(profiles, properties);
        RecommendRerankService rerank = new StableRerankService();
        RecommendSessionService sessions = new RecommendSessionServiceImpl(
                sessionStore, exposureService, contentQueryService, rerank, selector, redis, properties);

        AuthorProfileCache authors = Mockito.mock(AuthorProfileCache.class);
        when(authors.getAll(any())).thenReturn(Map.of());
        ContentInteractionService interactions = Mockito.mock(ContentInteractionService.class);
        HotContentService hotContent = Mockito.mock(HotContentService.class);
        when(hotContent.resolveRecommendHotKey(any())).thenReturn(RECOMMEND_HOT_ALL_KEY);
        FeedQueryServiceImpl feed = new FeedQueryServiceImpl(
                rerank, contentQueryService, authors, redis, hotContent, interactions, sessions, properties);

        ContentController contentController = new ContentController();
        ReflectionTestUtils.setField(contentController, "feedQueryService", feed);
        RecommendExposureController exposureController = new RecommendExposureController(exposureService, properties);
        mvc = MockMvcBuilders.standaloneSetup(contentController, exposureController)
                .setControllerAdvice(new RecommendProtocolAdvice())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(json))
                .build();
    }

    @AfterEach
    void tearDown() {
        BaseContext.removeCurrentId();
        if (redisConnectionFactory != null) {
            redisConnectionFactory.destroy();
        }
    }

    @Test
    void discoveryPagesReplayWithoutDuplicatesDeleteShrinkAndAllowRevisit() throws Exception {
        JsonNode first = discoveryPage(GUEST_A, SESSION_A, null, null);
        List<Long> firstIds = contentIds(first);
        assertThat(firstIds).hasSize(2);
        String nextCursor = requiredText(first, "nextCursor");

        JsonNode second = discoveryPage(GUEST_A, SESSION_A, nextCursor, null);
        List<Long> secondIds = contentIds(second);
        assertThat(secondIds).hasSize(2);
        Set<Long> overlap = new HashSet<>(firstIds);
        overlap.retainAll(secondIds);
        assertThat(overlap).isEmpty();
        assertThat(requiredText(second, "feedSessionId")).isEqualTo(SESSION_A);

        JsonNode replay = discoveryPage(GUEST_A, SESSION_A, nextCursor, null);
        assertThat(contentIds(replay)).containsExactlyElementsOf(secondIds);

        long deletedId = firstIds.get(0);
        jdbc.update("UPDATE tb_content SET is_deleted=1 WHERE content_id=?", deletedId);
        JsonNode shrunkReplay = discoveryPage(GUEST_A, SESSION_A, null, null);
        assertThat(contentIds(shrunkReplay)).hasSize(1).doesNotContain(deletedId);

        List<Long> exhaustedIds = new ArrayList<>();
        String cursor = null;
        JsonNode exhaustedPage = null;
        for (int pageNumber = 0; pageNumber < 6; pageNumber++) {
            exhaustedPage = discoveryPage(GUEST_B, SESSION_B, cursor, null);
            exhaustedIds.addAll(contentIds(exhaustedPage));
            if ("EXHAUSTED".equals(exhaustedPage.path("recommendationState").asText())) {
                break;
            }
            cursor = requiredText(exhaustedPage, "nextCursor");
        }
        assertThat(exhaustedPage).isNotNull();
        assertThat(exhaustedPage.path("recommendationState").asText()).isEqualTo("EXHAUSTED");
        assertThat(exhaustedPage.path("canRevisit").asBoolean()).isTrue();

        recordExposure(GUEST_B, SESSION_B, exhaustedIds);
        JsonNode revisit = discoveryPage(GUEST_B, SESSION_C, null, SESSION_B);
        assertThat(contentIds(revisit)).isNotEmpty();
        assertThat(requiredText(revisit, "feedSessionId")).isEqualTo(SESSION_C);
    }

    @Test
    void exposureStartsAFreshGuestPoolLoginWinsHeaderAndHotSkipsSession() throws Exception {
        JsonNode first = discoveryPage(GUEST_A, SESSION_D, null, null);
        List<Long> firstIds = contentIds(first);
        long exposedId = firstIds.get(0);
        recordExposure(GUEST_A, SESSION_D, List.of(exposedId));

        JsonNode refreshed = discoveryPage(GUEST_A, SESSION_E, null, null);
        assertThat(contentIds(refreshed)).doesNotContain(exposedId)
                .anyMatch(id -> firstIds.contains(id) && id != exposedId);

        JsonNode otherGuest = discoveryPage(GUEST_C, SESSION_A, null, null);
        assertThat(contentIds(otherGuest)).contains(exposedId);

        BaseContext.setCurrentId(42L);
        JsonNode loggedIn = discoveryPage("invalid-header-is-ignored", SESSION_B, null, null);
        assertThat(contentIds(loggedIn)).contains(exposedId);
        BaseContext.removeCurrentId();

        Set<String> sessionKeysBeforeHot = redis.keys(RedisConstants.RECOMMEND_V2_SESSION_KEY_PREFIX + "*");
        JsonNode hot = hotPage(GUEST_A);
        assertThat(hot.path("feedSessionId").isMissingNode() || hot.path("feedSessionId").isNull()).isTrue();
        assertThat(contentIds(hot)).contains(exposedId);
        Set<String> sessionKeysAfterHot = redis.keys(RedisConstants.RECOMMEND_V2_SESSION_KEY_PREFIX + "*");
        assertThat(sessionKeysAfterHot).isEqualTo(sessionKeysBeforeHot);
    }

    @Test
    void productionMapperUsesApprovedCategoryAndTimeIdCursor() {
        List<ContentSnapshotVO> candidates = contentQueryService.getApprovedRecommendCandidates(
                1, TIE_TIME.plusSeconds(1), 999L, null, null, 20);
        assertThat(candidates).extracting(ContentSnapshotVO::getContentId)
                .containsExactly(103L, 102L, 101L, 105L, 106L);

        List<ContentSnapshotVO> afterTieCursor = contentQueryService.getApprovedRecommendCandidates(
                1, TIE_TIME.plusSeconds(1), 999L, TIE_TIME, 102L, 20);
        assertThat(afterTieCursor).extracting(ContentSnapshotVO::getContentId)
                .containsExactly(101L, 105L, 106L);
        assertThat(contentQueryService.getApprovedRecommendUpperId(1, TIE_TIME.plusSeconds(1)))
                .isEqualTo(106L);
    }

    private JsonNode discoveryPage(String guestId, String sessionId, String pageCursor, String revisitOfSessionId)
            throws Exception {
        MockHttpServletRequestBuilder request = get("/content/recommend")
                .param("scene", "recommend")
                .param("pageSize", "2")
                .param("offset", "0")
                .param("feedSessionId", sessionId)
                .header("X-Guest-Id", guestId);
        if (pageCursor != null) {
            request.param("pageCursor", pageCursor);
        }
        if (revisitOfSessionId != null) {
            request.param("revisitOfSessionId", revisitOfSessionId);
        }
        return json.readTree(mvc.perform(request)
                        .andExpect(status().isOk())
                        .andReturn().getResponse().getContentAsString())
                .path("data");
    }

    private JsonNode hotPage(String guestId) throws Exception {
        return json.readTree(mvc.perform(get("/content/recommend")
                        .param("scene", "hot")
                        .param("pageSize", "2")
                        .param("offset", "0")
                        .header("X-Guest-Id", guestId))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString()).path("data");
    }

    private void recordExposure(String guestId, String sessionId, List<Long> contentIds) throws Exception {
        mvc.perform(post("/content/recommend/exposures")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Guest-Id", guestId)
                        .content(json.writeValueAsBytes(Map.of(
                                "feedSessionId", sessionId,
                                "contentIds", contentIds))))
                .andExpect(status().isOk())
                .andReturn();
    }

    private List<Long> contentIds(JsonNode page) {
        List<Long> ids = new ArrayList<>();
        page.path("list").forEach(item -> ids.add(item.path("contentId").asLong()));
        return ids;
    }

    private String requiredText(JsonNode node, String field) {
        String value = node.path(field).asText(null);
        assertThat(value).as(field).isNotBlank();
        return value;
    }

    private void resetMySqlFacts() {
        jdbc.update("DELETE FROM tb_content");
        jdbc.update("DELETE FROM tb_user");
        jdbc.update("INSERT INTO tb_user(id, nick_name, account_status, is_deleted) VALUES (1,'发布者',0,0),(42,'登录用户',0,0)");
        insertContent(101L, 1, TIE_TIME, 1, 1, 0);
        insertContent(102L, 1, TIE_TIME, 1, 1, 0);
        insertContent(103L, 1, TIE_TIME, 1, 1, 0);
        insertContent(104L, 2, TIE_TIME, 1, 1, 0);
        insertContent(105L, 1, TIE_TIME.minusSeconds(1), 1, 1, 0);
        insertContent(106L, 1, TIE_TIME.minusSeconds(2), 1, 1, 0);
        insertContent(107L, 1, TIE_TIME, 1, 0, 0);
        insertContent(108L, 1, TIE_TIME, 1, 1, 1);
    }

    private void insertContent(long contentId, int contentType, LocalDateTime createTime,
                               long publishUserId, int auditStatus, int deleted) {
        jdbc.update("""
                        INSERT INTO tb_content(
                            content_id, content_type, title, content, publish_user_id,
                            audit_status, liked, comment_count, collect_count, is_deleted,
                            create_time, update_time
                        ) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)
                        """,
                contentId, contentType, "内容" + contentId, "正文" + contentId, publishUserId,
                auditStatus, (int) contentId, 0, 0, deleted,
                Timestamp.valueOf(createTime), Timestamp.valueOf(createTime));
    }

    private void seedRecommendZSets() {
        for (long contentId = 101L; contentId <= 106L; contentId++) {
            redis.opsForZSet().add(RECOMMEND_HOT_ALL_KEY, Long.toString(contentId), contentId);
            redis.opsForZSet().add(RECOMMEND_ALL_KEY, Long.toString(contentId), contentId);
        }
    }

    private void deleteRedisKeys() {
        Set<String> keys = redis.keys("*");
        if (keys != null && !keys.isEmpty()) {
            redis.delete(new ArrayList<>(keys));
        }
    }

    private static final class StableRerankService implements RecommendRerankService {
        @Override
        public RerankResult rerank(Long userId, Integer contentType, int pageSize) {
            return new RerankResult(List.of(), false);
        }

        @Override
        public List<ContentSnapshotVO> rankCandidates(Long userId, List<ContentSnapshotVO> candidates) {
            return candidates.stream()
                    .sorted((left, right) -> Long.compare(right.getContentId(), left.getContentId()))
                    .toList();
        }
    }
}
