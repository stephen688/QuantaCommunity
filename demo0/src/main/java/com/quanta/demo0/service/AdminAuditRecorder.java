package com.quanta.demo0.service;

/**
 * 管理员审计记录器。
 */
public interface AdminAuditRecorder {

    void recordSuccess(
            String action,
            String targetType,
            String targetId,
            String beforeSummary,
            String afterSummary
    );

    void recordFailure(
            String action,
            String targetType,
            String targetId,
            Throwable throwable
    );
}
