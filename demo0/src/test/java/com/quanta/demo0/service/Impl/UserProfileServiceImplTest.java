package com.quanta.demo0.service.Impl;

import com.quanta.demo0.constant.RedisConstants;
import com.quanta.demo0.entity.Content;
import com.quanta.demo0.mapper.ContentMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * UserProfileServiceImpl 的画像 Hash 读写测试（mock Mapper 与 Redis）。
 * 真实 Redis 的 HINCRBYFLOAT 行为由 03 分计划的 Testcontainers 集成测试覆盖。
 */
class UserProfileServiceImplTest {

    private static final Long USER_ID = 3L;
    private static final Long CONTENT_ID = 42L;
    private static final String PROFILE_KEY = RedisConstants.USER_PROFILE_KEY + USER_ID;

    private ContentMapper contentMapper;
    private StringRedisTemplate stringRedisTemplate;
    private HashOperations<String, Object, Object> hashOperations;
    private UserProfileServiceImpl service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        contentMapper = mock(ContentMapper.class);
        stringRedisTemplate = mock(StringRedisTemplate.class);
        hashOperations = mock(HashOperations.class);
        when(stringRedisTemplate.opsForHash()).thenReturn(hashOperations);
        service = new UserProfileServiceImpl(contentMapper, stringRedisTemplate);
    }

    private Content approvedContent(Integer contentType) {
        return Content.builder()
                .contentId(CONTENT_ID)
                .contentType(contentType)
                .auditStatus(1)  // 审核通过
                .isDeleted(0)    // 未删除
                .build();
    }

    @Test
    void 合法帖type1审核通过_life标签与total各累加权重() {
        when(contentMapper.selectById(CONTENT_ID)).thenReturn(approvedContent(1));

        service.applyBehavior(USER_ID, CONTENT_ID, 2.0);

        // life 标签与 __total 同步累加同一权重
        verify(hashOperations).increment(PROFILE_KEY, "life", 2.0);
        verify(hashOperations).increment(PROFILE_KEY, RedisConstants.USER_PROFILE_TOTAL_FIELD, 2.0);
    }

    @Test
    void 合法帖type2审核通过_professional标签累加() {
        when(contentMapper.selectById(CONTENT_ID)).thenReturn(approvedContent(2));

        service.applyBehavior(USER_ID, CONTENT_ID, 2.0);

        // professional 标签与 __total 同步累加同一权重
        verify(hashOperations).increment(PROFILE_KEY, "professional", 2.0);
        verify(hashOperations).increment(PROFILE_KEY, RedisConstants.USER_PROFILE_TOTAL_FIELD, 2.0);
    }

    @Test
    void 帖子不存在_零写入零异常() {
        when(contentMapper.selectById(CONTENT_ID)).thenReturn(null);

        assertDoesNotThrow(() -> service.applyBehavior(USER_ID, CONTENT_ID, 2.0));

        verify(hashOperations, never()).increment(anyString(), any(), anyDouble());
    }

    @Test
    void 帖子已驳回_零写入零异常() {
        Content rejected = approvedContent(1);
        rejected.setAuditStatus(2);  // 已驳回
        when(contentMapper.selectById(CONTENT_ID)).thenReturn(rejected);

        assertDoesNotThrow(() -> service.applyBehavior(USER_ID, CONTENT_ID, 2.0));

        verify(hashOperations, never()).increment(anyString(), any(), anyDouble());
    }

    @Test
    void 帖子已删除_零写入零异常() {
        Content deleted = approvedContent(1);
        deleted.setIsDeleted(1);  // 已软删除
        when(contentMapper.selectById(CONTENT_ID)).thenReturn(deleted);

        assertDoesNotThrow(() -> service.applyBehavior(USER_ID, CONTENT_ID, 2.0));

        verify(hashOperations, never()).increment(anyString(), any(), anyDouble());
    }

    @Test
    void redis异常向上抛_不吞掉伪装成功() {
        when(contentMapper.selectById(CONTENT_ID)).thenReturn(approvedContent(1));
        when(hashOperations.increment(anyString(), any(), anyDouble()))
                .thenThrow(new RedisConnectionFailureException("redis down"));

        // Redis 故障必须向上抛，由调用方（消费者）决定重试语义
        assertThrows(RedisConnectionFailureException.class,
                () -> service.applyBehavior(USER_ID, CONTENT_ID, 2.0));
    }

    @Test
    void getProfile_委托hashEntries并转换为Double() {
        Map<Object, Object> raw = new HashMap<>();
        raw.put("life", "2.0");
        raw.put(RedisConstants.USER_PROFILE_TOTAL_FIELD, "5.0");
        when(hashOperations.entries(PROFILE_KEY)).thenReturn(raw);

        Map<String, Double> profile = service.getProfile(USER_ID);

        assertEquals(2, profile.size());
        assertEquals(2.0, profile.get("life"));
        assertEquals(5.0, profile.get(RedisConstants.USER_PROFILE_TOTAL_FIELD));
    }

    @Test
    void getProfile_无画像返回空Map() {
        when(hashOperations.entries(PROFILE_KEY)).thenReturn(null);

        Map<String, Double> profile = service.getProfile(USER_ID);

        assertNotNull(profile);
        assertTrue(profile.isEmpty());
    }

    @Test
    void getProfile_null用户ID防御返回空Map() {
        Map<String, Double> profile = service.getProfile(null);

        assertNotNull(profile);
        assertTrue(profile.isEmpty());
    }
}
