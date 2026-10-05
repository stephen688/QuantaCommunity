package com.quanta.demo0.feed.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.quanta.demo0.content.exception.ContentFailedException;
import com.quanta.demo0.feed.dto.RecommendQueryDTO;
import com.quanta.demo0.feed.dto.RecommendVisitor;
import com.quanta.demo0.feed.exception.RecommendSessionExpiredException;
import com.quanta.demo0.feed.properties.RecommendProperties;
import com.quanta.demo0.feed.service.RecommendExposureService;
import com.quanta.demo0.feed.vo.RecommendSessionSnapshot;
import com.quanta.demo0.platform.redis.constant.RedisConstants;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 推荐发现会话与真实曝光的 Redis 集成测试。
 *
 * <p>只使用 recommend:v2 命名空间，验证 Redis 原子脚本、会话页重放、租约 owner
 * 校验和逐条曝光失效；内容查询与 HTTP 门面由其它定向测试负责。</p>
 */
@Testcontainers
class RecommendDiscoveryRedisIntegrationTests {

    @Container
    private static final GenericContainer<?> REDIS = new GenericContainer<>(
            DockerImageName.parse("redis:7.2-alpine"))
            .withExposedPorts(6379);

    private LettuceConnectionFactory connectionFactory;
    private StringRedisTemplate redisTemplate;
    private RecommendProperties properties;
    private RecommendSessionStore sessionStore;
    private RecommendExposureService exposureService;

    @BeforeEach
    void setUp() {
        connectionFactory = new LettuceConnectionFactory(
                REDIS.getHost(), REDIS.getMappedPort(6379));
        connectionFactory.afterPropertiesSet();
        redisTemplate = new StringRedisTemplate(connectionFactory);
        redisTemplate.afterPropertiesSet();
        deleteV2Keys();

        properties = new RecommendProperties();
        sessionStore = new RecommendSessionStore(redisTemplate, new ObjectMapper(), properties);
        exposureService = new RecommendExposureServiceImpl(redisTemplate, sessionStore, properties);
    }

    @AfterEach
    void tearDown() {
        if (connectionFactory != null) {
            connectionFactory.destroy();
        }
    }

    @Test
    void concurrentCreateForSameSessionReturnsOneImmutableRound() {
        RecommendVisitor visitor = RecommendVisitor.from(
                null, "9845e25a-4e42-4f42-8f71-964ec8a68fb5");
        RecommendQueryDTO query = RecommendQueryDTO.builder()
                .contentType(1)
                .pageSize(5)
                .build();
        String sessionId = "7fac5fa8-8b33-48ec-bd5b-6c2e8e762fc0";
        Set<Long> seeds = ConcurrentHashMap.newKeySet();

        IntStream.range(0, 8).parallel().forEach(ignored ->
                seeds.add(sessionStore.create(visitor, sessionId, query).getSeed()));

        assertThat(seeds).hasSize(1);
        assertThat(sessionStore.load(visitor, sessionId).getPageSize()).isEqualTo(5);
    }

    @Test
    void pageCommitAndUnlockRequireTheCurrentOwnerAndReplayTheSamePage() {
        RecommendVisitor visitor = RecommendVisitor.from(
                null, "b1c2d3e4-f5a6-47b8-89c0-d1e2f3a4b5c6");
        String sessionId = "4b8e2d57-67dd-4e11-9c1a-68e3e7ab680a";
        sessionStore.create(visitor, sessionId, RecommendQueryDTO.builder().pageSize(5).build());

        assertThat(sessionStore.tryLock(visitor, sessionId, "owner-a")).isTrue();
        assertThat(sessionStore.tryLock(visitor, sessionId, "owner-b")).isFalse();

        RecommendSessionSnapshot state = sessionStore.load(visitor, sessionId);
        state.setDeliveredIds(new HashSet<>(List.of(101L, 102L)));
        state.setTotalDelivered(2);
        state.setPagesByCursor(new HashMap<>(Map.of("", List.of(101L, 102L))));
        state.setCurrentNextCursor("cursor-2");
        state.setPendingIds(new ArrayDeque<>(List.of(103L)));

        assertThat(sessionStore.commitPage(visitor, sessionId, "owner-b", state)).isFalse();
        assertThat(sessionStore.commitPage(visitor, sessionId, "owner-a", state)).isTrue();
        assertThat(sessionStore.loadPage(visitor, sessionId, ""))
                .containsExactly(101L, 102L);
        assertThat(sessionStore.unlock(visitor, sessionId, "owner-b")).isFalse();
        assertThat(sessionStore.unlock(visitor, sessionId, "owner-a")).isTrue();
    }

    @Test
    void exposureIsBoundToDeliveredIdsAndDuplicateReportDoesNotRenewItem() throws Exception {
        RecommendVisitor visitor = RecommendVisitor.from(
                null, "c1d2e3f4-a5b6-47c8-89d0-e1f2a3b4c5d6");
        String sessionId = "f4f5b6c7-d8e9-4a01-b2c3-d4e5f6a7b8c9";
        sessionStore.create(visitor, sessionId, RecommendQueryDTO.builder().pageSize(5).build());
        assertThat(sessionStore.tryLock(visitor, sessionId, "owner" )).isTrue();
        RecommendSessionSnapshot state = sessionStore.load(visitor, sessionId);
        state.setDeliveredIds(new HashSet<>(List.of(101L, 102L)));
        state.setPagesByCursor(new HashMap<>(Map.of("", List.of(101L, 102L))));
        state.setTotalDelivered(2);
        assertThat(sessionStore.commitPage(visitor, sessionId, "owner", state)).isTrue();
        assertThat(sessionStore.unlock(visitor, sessionId, "owner")).isTrue();

        exposureService.record(visitor, sessionId, List.of(101L));
        String key = RedisConstants.RECOMMEND_V2_EXPOSURE_KEY_PREFIX + visitor.actorKey();
        Double firstExpiry = redisTemplate.opsForZSet().score(key, "101");
        assertThat(firstExpiry).isNotNull();

        Thread.sleep(2L);
        exposureService.record(visitor, sessionId, List.of(101L));
        assertThat(redisTemplate.opsForZSet().score(key, "101"))
                .isEqualTo(firstExpiry);
        assertThat(exposureService.findExposed(visitor, List.of(101L, 102L)))
                .containsExactly(101L);

        assertThatThrownBy(() -> exposureService.record(visitor, sessionId, List.of(999L)))
                .isInstanceOf(ContentFailedException.class);

        redisTemplate.opsForZSet().add(key, "101", System.currentTimeMillis() - 1L);
        assertThat(exposureService.findExposed(visitor, List.of(101L, 102L))).isEmpty();
    }

    @Test
    void expiredSessionIdCannotBeRevivedAfterItsPayloadDisappears() {
        RecommendVisitor visitor = RecommendVisitor.from(
                null, "d1e2f3a4-b5c6-47d8-89e0-f1a2b3c4d5e6");
        String sessionId = "a1b2c3d4-e5f6-4789-90ab-c1d2e3f4a5b6";
        RecommendQueryDTO query = RecommendQueryDTO.builder().pageSize(5).build();
        sessionStore.create(visitor, sessionId, query);
        String sessionKey = RedisConstants.RECOMMEND_V2_SESSION_KEY_PREFIX
                + visitor.actorKey() + ":" + sessionId;
        redisTemplate.delete(sessionKey);

        assertThatThrownBy(() -> sessionStore.create(visitor, sessionId, query))
                .isInstanceOf(RecommendSessionExpiredException.class);
    }

    private void deleteV2Keys() {
        Set<String> keys = redisTemplate.keys(RedisConstants.RECOMMEND_V2_PREFIX + "*");
        if (keys != null && !keys.isEmpty()) {
            redisTemplate.delete(new ArrayList<>(keys));
        }
    }
}
