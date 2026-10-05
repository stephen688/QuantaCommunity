package com.quanta.demo0.platform.web.idempotency.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.quanta.demo0.platform.web.idempotency.entity.HttpSubmission;
import com.quanta.demo0.platform.web.idempotency.enums.SubmissionStatus;
import com.quanta.demo0.platform.web.idempotency.exception.SubmissionException;
import com.quanta.demo0.platform.web.idempotency.mapper.SubmissionMapper;
import com.quanta.demo0.platform.web.idempotency.properties.SubmissionProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.function.Supplier;

/**
 * HTTP 提交幂等事务代理。
 *
 * 职责：在一个 REQUIRED 事务中插入凭证、调用领域写入并保存首次响应；边界：不负责 Redis 占位。
 */
@Service
@RequiredArgsConstructor
public class SubmissionTransactionService {

    private final SubmissionMapper submissionMapper;
    private final ObjectMapper objectMapper;
    private final SubmissionProperties properties;

    /**
     * 将凭证和领域写入加入同一数据库事务。
     *
     * <p>唯一键冲突由调用方在事务代理外捕获；领域异常会让本事务整体回滚，
     * 不留下持久 PROCESSING 记录。</p>
     */
    @Transactional
    public <T> T execute(Long userId, String scene, String token, String requestHash,
                         Class<T> responseType, Supplier<T> operation) {
        LocalDateTime now = LocalDateTime.now();
        HttpSubmission submission = HttpSubmission.builder()
                .userId(userId)
                .scene(scene)
                .submissionToken(token)
                .requestHash(requestHash)
                .status(SubmissionStatus.PROCESSING)
                .createTime(now)
                .expiresAt(now.plusDays(properties.getRetentionDays()))
                .build();
        if (submissionMapper.insert(submission) != 1 || submission.getId() == null) {
            throw new IllegalStateException("创建提交凭证失败");
        }

        T response = operation.get();
        String responseData = serializeResponse(response);
        if (responseData.getBytes(StandardCharsets.UTF_8).length > properties.getMaxResponseBytes()) {
            throw SubmissionException.badRequest("提交响应过大，无法保存幂等结果");
        }
        if (submissionMapper.markSucceeded(submission.getId(), responseData) != 1) {
            throw new IllegalStateException("保存提交结果失败");
        }
        return response;
    }

    private String serializeResponse(Object response) {
        try {
            return objectMapper.writeValueAsString(response);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("提交结果无法保存", exception);
        }
    }
}
