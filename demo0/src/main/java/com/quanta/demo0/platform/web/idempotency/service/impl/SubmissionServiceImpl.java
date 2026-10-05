package com.quanta.demo0.platform.web.idempotency.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.quanta.demo0.answer.vo.AnswerVO;
import com.quanta.demo0.content.vo.ContentVO;
import com.quanta.demo0.platform.security.constant.RoleConstants;
import com.quanta.demo0.platform.security.context.BaseContext;
import com.quanta.demo0.platform.security.properties.QuantabotProperties;
import com.quanta.demo0.platform.web.idempotency.entity.HttpSubmission;
import com.quanta.demo0.platform.web.idempotency.enums.SubmissionScene;
import com.quanta.demo0.platform.web.idempotency.enums.SubmissionStatus;
import com.quanta.demo0.platform.web.idempotency.exception.SubmissionException;
import com.quanta.demo0.platform.web.idempotency.mapper.SubmissionMapper;
import com.quanta.demo0.platform.web.idempotency.properties.SubmissionProperties;
import com.quanta.demo0.platform.web.idempotency.service.SubmissionService;
import com.quanta.demo0.platform.web.idempotency.utils.SubmissionRequestHasher;
import com.quanta.demo0.platform.web.idempotency.vo.SubmissionStatusVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionException;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * HTTP 提交幂等编排实现。
 *
 * 职责：先查主库、再以 Redis NX 减少并发重复、最后交给独立事务代理；边界：不直接调用领域 Mapper。
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class SubmissionServiceImpl implements SubmissionService {

    private static final Pattern UUID_V4_PATTERN = Pattern.compile(
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-4[0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}$");
    private static final String REDIS_KEY_PREFIX = "submission:processing:";
    private static final DefaultRedisScript<Long> RELEASE_SCRIPT = createReleaseScript();

    private final SubmissionMapper submissionMapper;
    private final SubmissionTransactionService transactionService;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final SubmissionProperties properties;
    private final QuantabotProperties quantabotProperties;

    private static DefaultRedisScript<Long> createReleaseScript() {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        try (InputStream input = new ClassPathResource("lua/submission_release.lua").getInputStream()) {
            script.setScriptText(new String(input.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException exception) {
            throw new IllegalStateException("加载提交占位清理 Lua 失败", exception);
        }
        script.setResultType(Long.class);
        return script;
    }

    /**
     * 执行带提交凭证的领域写入。
     *
     * <p>Redis 只承担并发期间的快速占位；唯一键、事务和原响应才是最终防重复边界。
     * 缺失凭证在兼容模式按旧路径调用，强制模式下仅保留已认证 BOT 的旧回帖兼容。</p>
     */
    @Override
    public <T> T execute(String scene, String token, Object request,
                         Class<T> responseType, Supplier<T> operation) {
        Long userId = requireUserId();
        validateScene(scene);
        if (!StringUtils.hasText(token)) {
            if (!properties.isRequireUserKey() || isAuthenticatedBot(userId)) {
                return operation.get();
            }
            throw SubmissionException.badRequest("缺少提交凭证");
        }

        String normalizedToken = normalizeToken(token);
        String requestHash = SubmissionRequestHasher.sha256(request, objectMapper);
        HttpSubmission existing = submissionMapper.selectByUserSceneToken(
                userId, scene, normalizedToken);
        T replay = resolveExisting(existing, requestHash, responseType);
        if (existing != null) {
            return replay;
        }

        String owner = UUID.randomUUID().toString();
        String redisKey = buildRedisKey(userId, scene, normalizedToken);
        boolean reserved = reserve(redisKey, owner);
        if (!reserved) {
            existing = submissionMapper.selectByUserSceneToken(userId, scene, normalizedToken);
            replay = resolveExisting(existing, requestHash, responseType);
            if (existing != null) {
                return replay;
            }
            throw SubmissionException.unconfirmed();
        }

        boolean retainReservation = false;
        try {
            // Redis 占位之后再次查主库，覆盖占位前已提交但尚未被第一次查询看到的情况。
            existing = submissionMapper.selectByUserSceneToken(userId, scene, normalizedToken);
            replay = resolveExisting(existing, requestHash, responseType);
            if (existing != null) {
                return replay;
            }
            try {
                return transactionService.execute(
                        userId,
                        scene,
                        normalizedToken,
                        requestHash,
                        responseType,
                        operation);
            } catch (DataIntegrityViolationException exception) {
                // 唯一键竞争必须在事务代理退出后重新查询，不能在 rollback-only 事务里继续写。
                existing = submissionMapper.selectByUserSceneToken(userId, scene, normalizedToken);
                replay = resolveExisting(existing, requestHash, responseType);
                if (existing != null) {
                    return replay;
                }
                retainReservation = true;
                throw SubmissionException.unconfirmed();
            } catch (DataAccessException | TransactionException exception) {
                // 提交结果可能未知，保留短 TTL 占位，交给原 Token 查询/重试确认。
                retainReservation = true;
                log.warn("提交事务结果待确认: scene={}, userId={}, errorType={}",
                        scene, userId, exception.getClass().getSimpleName());
                throw SubmissionException.unconfirmed();
            }
        } finally {
            if (!retainReservation) {
                release(redisKey, owner);
            }
        }
    }

    /** 查询主库中当前登录用户自己的凭证状态。 */
    @Override
    public SubmissionStatusVO query(String scene, String token) {
        Long userId = requireUserId();
        validateScene(scene);
        String normalizedToken = normalizeToken(token);
        HttpSubmission submission = submissionMapper.selectByUserSceneToken(
                userId, scene, normalizedToken);
        if (submission == null) {
            return SubmissionStatusVO.builder()
                    .scene(scene)
                    .status(SubmissionStatus.UNCONFIRMED)
                    .build();
        }
        if (isExpired(submission)) {
            return SubmissionStatusVO.builder()
                    .scene(scene)
                    .status(SubmissionStatus.EXPIRED)
                    .expiresAt(submission.getExpiresAt())
                    .build();
        }
        if (!SubmissionStatus.SUCCEEDED.equals(submission.getStatus())) {
            return SubmissionStatusVO.builder()
                    .scene(scene)
                    .status(SubmissionStatus.UNCONFIRMED)
                    .expiresAt(submission.getExpiresAt())
                    .build();
        }
        return SubmissionStatusVO.builder()
                .scene(scene)
                .status(SubmissionStatus.SUCCEEDED)
                .data(readResponse(submission.getResponseData(), responseType(scene)))
                .expiresAt(submission.getExpiresAt())
                .build();
    }

    private <T> T resolveExisting(HttpSubmission existing, String requestHash,
                                  Class<T> responseType) {
        if (existing == null) {
            return null;
        }
        if (isExpired(existing)) {
            throw SubmissionException.expired();
        }
        if (!requestHash.equals(existing.getRequestHash())) {
            throw SubmissionException.conflict("提交凭证与内容不一致");
        }
        if (!SubmissionStatus.SUCCEEDED.equals(existing.getStatus())) {
            throw SubmissionException.unconfirmed();
        }
        return readResponse(existing.getResponseData(), responseType);
    }

    private boolean reserve(String key, String owner) {
        try {
            Boolean acquired = redisTemplate.opsForValue().setIfAbsent(
                    key,
                    owner,
                    properties.getProcessingTtlSeconds(),
                    java.util.concurrent.TimeUnit.SECONDS);
            // RedisTemplate 对 NX 的空返回只表示未得到明确结果，交给数据库唯一键兜底。
            return acquired == null || acquired;
        } catch (RuntimeException exception) {
            log.warn("幂等 Redis 占位失败，降级到主库唯一键: errorType={}",
                    exception.getClass().getSimpleName());
            return true;
        }
    }

    private void release(String key, String owner) {
        try {
            redisTemplate.execute(RELEASE_SCRIPT, List.of(key), owner);
        } catch (RuntimeException exception) {
            // 清理失败只影响短暂占位，不能把已经提交的业务解释为失败。
            log.warn("幂等 Redis 占位清理失败: errorType={}",
                    exception.getClass().getSimpleName());
        }
    }

    private String buildRedisKey(Long userId, String scene, String token) {
        return REDIS_KEY_PREFIX + userId + ":" + scene + ":" + token;
    }

    private Long requireUserId() {
        Long userId = BaseContext.getCurrentId();
        if (userId == null) {
            throw SubmissionException.badRequest("当前用户未登录");
        }
        return userId;
    }

    private void validateScene(String scene) {
        if (!SubmissionScene.isSupported(scene)) {
            throw SubmissionException.badRequest("提交场景不支持");
        }
    }

    private String normalizeToken(String token) {
        if (!StringUtils.hasText(token) || !UUID_V4_PATTERN.matcher(token.trim()).matches()) {
            throw SubmissionException.badRequest("提交凭证格式错误");
        }
        return UUID.fromString(token.trim()).toString().toLowerCase(java.util.Locale.ROOT);
    }

    private boolean isExpired(HttpSubmission submission) {
        return submission.getExpiresAt() != null
                && !LocalDateTime.now().isBefore(submission.getExpiresAt());
    }

    private Class<?> responseType(String scene) {
        return switch (scene) {
            case SubmissionScene.CONTENT_PUBLISH -> ContentVO.class;
            case SubmissionScene.ANSWER_PUBLISH -> AnswerVO.class;
            case SubmissionScene.COMMENT_SEND -> Long.class;
            default -> throw SubmissionException.badRequest("提交场景不支持");
        };
    }

    private <T> T readResponse(String responseData, Class<T> responseType) {
        if (responseData == null || responseData.isBlank() || responseType == Void.class) {
            return null;
        }
        try {
            return objectMapper.readValue(responseData, responseType);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("保存的提交结果无法读取", exception);
        }
    }

    private boolean isAuthenticatedBot(Long userId) {
        if (quantabotProperties.getBotUserId() == null
                || !quantabotProperties.getBotUserId().equals(userId)) {
            return false;
        }
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication != null
                && authentication.isAuthenticated()
                && authentication.getAuthorities().stream()
                .anyMatch(authority -> ("ROLE_" + RoleConstants.BOT)
                        .equals(authority.getAuthority()));
    }
}
