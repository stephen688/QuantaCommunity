package com.quanta.demo0.platform.web.idempotency;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.quanta.demo0.platform.security.context.BaseContext;
import com.quanta.demo0.platform.web.idempotency.entity.HttpSubmission;
import com.quanta.demo0.platform.web.idempotency.enums.SubmissionScene;
import com.quanta.demo0.platform.web.idempotency.enums.SubmissionStatus;
import com.quanta.demo0.platform.web.idempotency.exception.SubmissionException;
import com.quanta.demo0.platform.web.idempotency.mapper.SubmissionMapper;
import com.quanta.demo0.platform.web.idempotency.properties.SubmissionProperties;
import com.quanta.demo0.platform.web.idempotency.service.impl.SubmissionServiceImpl;
import com.quanta.demo0.platform.web.idempotency.service.impl.SubmissionTransactionService;
import com.quanta.demo0.platform.web.idempotency.utils.SubmissionRequestHasher;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 公共 HTTP 提交幂等服务的最小行为测试。 */
@ExtendWith(MockitoExtension.class)
class SubmissionServiceTests {

    private static final String TOKEN = "123e4567-e89b-42d3-a456-426614174000";

    @Mock
    private SubmissionMapper submissionMapper;
    @Mock
    private SubmissionTransactionService transactionService;
    @Mock
    private StringRedisTemplate redisTemplate;
    @Mock
    private ValueOperations<String, String> valueOperations;

    private SubmissionProperties properties;
    private SubmissionServiceImpl submissionService;

    @BeforeEach
    void setUp() {
        BaseContext.setCurrentId(7L);
        properties = new SubmissionProperties();
        properties.setRequireUserKey(true);
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        lenient().when(valueOperations.setIfAbsent(anyString(), anyString(), anyLong(), any()))
                .thenReturn(true);
        submissionService = new SubmissionServiceImpl(
                submissionMapper,
                transactionService,
                redisTemplate,
                new ObjectMapper(),
                properties,
                new com.quanta.demo0.platform.security.properties.QuantabotProperties());
    }

    @AfterEach
    void tearDown() {
        BaseContext.removeCurrentId();
        SecurityContextHolder.clearContext();
    }

    @Test
    void replayWithSameTokenAndPayloadReturnsStoredResultWithoutRunningOperation() {
        Map<String, Object> request = Map.of("content", "hello", "contentId", 12L);
        String hash = SubmissionRequestHasher.sha256(request, new ObjectMapper());
        HttpSubmission stored = HttpSubmission.builder()
                .id(3L)
                .userId(7L)
                .scene(SubmissionScene.COMMENT_SEND)
                .submissionToken(TOKEN)
                .requestHash(hash)
                .status(SubmissionStatus.SUCCEEDED)
                .responseData("88")
                .createTime(LocalDateTime.now().minusMinutes(1))
                .expiresAt(LocalDateTime.now().plusDays(7))
                .build();
        when(submissionMapper.selectByUserSceneToken(7L, SubmissionScene.COMMENT_SEND, TOKEN))
                .thenReturn(stored);
        AtomicInteger calls = new AtomicInteger();

        Long result = submissionService.execute(
                SubmissionScene.COMMENT_SEND,
                TOKEN,
                request,
                Long.class,
                () -> {
                    calls.incrementAndGet();
                    return 99L;
                });

        assertEquals(88L, result);
        assertEquals(0, calls.get());
        verify(transactionService, never()).execute(any(), anyString(), anyString(), anyString(), any(), any());
    }

    @Test
    void sameTokenWithDifferentPayloadIsRejectedBeforeOperation() {
        Map<String, Object> originalRequest = Map.of("content", "hello", "contentId", 12L);
        String hash = SubmissionRequestHasher.sha256(originalRequest, new ObjectMapper());
        when(submissionMapper.selectByUserSceneToken(7L, SubmissionScene.COMMENT_SEND, TOKEN))
                .thenReturn(HttpSubmission.builder()
                        .id(3L)
                        .userId(7L)
                        .scene(SubmissionScene.COMMENT_SEND)
                        .submissionToken(TOKEN)
                        .requestHash(hash)
                        .status(SubmissionStatus.SUCCEEDED)
                        .responseData("88")
                        .expiresAt(LocalDateTime.now().plusDays(7))
                        .build());

        SubmissionException exception = assertThrows(
                SubmissionException.class,
                () -> submissionService.execute(
                        SubmissionScene.COMMENT_SEND,
                        TOKEN,
                        Map.of("content", "changed", "contentId", 12L),
                        Long.class,
                        () -> 99L));

        assertEquals(409, exception.getStatusCode());
        assertEquals("提交凭证与内容不一致", exception.getMessage());
        verify(transactionService, never()).execute(any(), anyString(), anyString(), anyString(), any(), any());
    }

    @Test
    void missingTokenIsRejectedWhenCompatibilitySwitchIsEnabled() {
        SubmissionException exception = assertThrows(
                SubmissionException.class,
                () -> submissionService.execute(
                        SubmissionScene.COMMENT_SEND,
                        null,
                        Map.of("content", "hello"),
                        Long.class,
                        () -> 1L));

        assertEquals(400, exception.getStatusCode());
        assertEquals("缺少提交凭证", exception.getMessage());
    }

    @Test
    void missingTokenCompatibilityRequiresBothConfiguredBotIdentityAndBotRole() {
        var botProperties = new com.quanta.demo0.platform.security.properties.QuantabotProperties();
        botProperties.setBotUserId(7L);
        var service = new SubmissionServiceImpl(submissionMapper, transactionService,
                redisTemplate, new ObjectMapper(), properties, botProperties);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("bot", null,
                        java.util.List.of(new SimpleGrantedAuthority("ROLE_BOT"))));
        assertEquals(88L, service.execute(SubmissionScene.COMMENT_SEND,
                null, Map.of("content", "bot reply"), Long.class, () -> 88L));

        BaseContext.setCurrentId(8L);
        assertEquals(400, assertThrows(SubmissionException.class,
                () -> service.execute(SubmissionScene.COMMENT_SEND, null,
                        Map.of("content", "wrong identity"), Long.class, () -> 99L)).getStatusCode());
        BaseContext.setCurrentId(7L);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("user", null,
                        java.util.List.of(new SimpleGrantedAuthority("ROLE_VERIFIED_USER"))));
        assertEquals(400, assertThrows(SubmissionException.class,
                () -> service.execute(SubmissionScene.COMMENT_SEND, null,
                        Map.of("content", "wrong role"), Long.class, () -> 99L)).getStatusCode());
    }

    @Test
    void requestHashIgnoresObjectPropertyOrderButPreservesArrayOrder() {
        ObjectMapper mapper = new ObjectMapper();
        String first = SubmissionRequestHasher.sha256(
                Map.of("b", 2, "a", 1, "images", java.util.List.of("one", "two")), mapper);
        String reordered = SubmissionRequestHasher.sha256(
                Map.of("images", java.util.List.of("one", "two"), "a", 1, "b", 2), mapper);
        String arrayChanged = SubmissionRequestHasher.sha256(
                Map.of("a", 1, "b", 2, "images", java.util.List.of("two", "one")), mapper);

        assertEquals(first, reordered);
        org.junit.jupiter.api.Assertions.assertNotEquals(first, arrayChanged);
    }
}
