package com.quanta.demo0.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 管理员审计日志。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AdminAuditLog {

    private Long id;
    private String requestId;
    private Long operatorId;
    private String operatorRoles;
    private String action;
    private String targetType;
    private String targetId;
    private String httpMethod;
    private String requestPath;
    private String beforeSummary;
    private String afterSummary;
    private String resultStatus;
    private String errorCode;
    private String errorMessage;
    private String clientIp;
    private String userAgent;
    private LocalDateTime createdAt;
}
