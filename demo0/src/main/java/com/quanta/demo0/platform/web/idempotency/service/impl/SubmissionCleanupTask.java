package com.quanta.demo0.platform.web.idempotency.service.impl;

import com.quanta.demo0.platform.web.idempotency.mapper.SubmissionMapper;
import com.quanta.demo0.platform.web.idempotency.properties.SubmissionProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * HTTP 提交凭证清理任务。
 *
 * 职责：每日分批删除已成功且超过 7 天的凭证；边界：不触碰业务表或 Outbox。
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class SubmissionCleanupTask {

    private static final int MAX_BATCHES_PER_RUN = 20;

    private final SubmissionMapper submissionMapper;
    private final SubmissionProperties properties;

    /** 删除一批已过期成功凭证，失败留给下一次调度重试。 */
    @Scheduled(cron = "${quanta.submission-idempotency.cleanup-cron:0 20 3 * * ?}")
    public void cleanupExpiredSubmissions() {
        int totalDeleted = 0;
        int deleted;
        int batches = 0;
        do {
            deleted = submissionMapper.deleteExpiredSucceeded(
                    LocalDateTime.now(), properties.getCleanupBatchSize());
            totalDeleted += deleted;
            batches++;
        } while (deleted >= properties.getCleanupBatchSize()
                && batches < MAX_BATCHES_PER_RUN);
        if (totalDeleted > 0) {
            log.info("清理已完成过期提交凭证: count={}, batches={}", totalDeleted, batches);
        }
    }
}
