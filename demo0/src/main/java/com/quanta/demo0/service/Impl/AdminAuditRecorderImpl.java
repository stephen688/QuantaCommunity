package com.quanta.demo0.service.Impl;

import com.quanta.demo0.entity.AdminAuditLog;
import com.quanta.demo0.mapper.AdminAuditLogMapper;
import com.quanta.demo0.security.AuthenticatedUser;
import com.quanta.demo0.service.AdminAuditRecorder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import jakarta.servlet.http.HttpServletRequest;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * 管理员审计记录器实现类。
 *
 * 成功审计要求外面已经有业务事务（MANDATORY），
 * 失败审计委托给独立新事务的写入器（REQUIRES_NEW）。
 */
@Service
@Slf4j
public class AdminAuditRecorderImpl
        implements AdminAuditRecorder {

    /**
     * 脱敏摘要最大长度。
     */
    private static final int SUMMARY_MAX_LENGTH = 2000;

    /**
     * 错误信息最大长度。
     */
    private static final int ERROR_MESSAGE_MAX_LENGTH = 1000;

    @Autowired
    private AdminAuditLogMapper adminAuditLogMapper;

    @Autowired
    private AdminAuditFailureWriter adminAuditFailureWriter;

    /**
     * 成功审计：跟随业务事务。
     * MANDATORY表示调用时必须已经存在业务事务。
     */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void recordSuccess(
            String action,
            String targetType,
            String targetId,
            String beforeSummary,
            String afterSummary
    ) {
        AdminAuditLog auditLog = buildCommonAuditLog();
        auditLog.setAction(action);
        auditLog.setTargetType(targetType);
        auditLog.setTargetId(targetId);
        auditLog.setBeforeSummary(limitSummary(beforeSummary));
        auditLog.setAfterSummary(limitSummary(afterSummary));
        auditLog.setResultStatus("SUCCESS");

        if (adminAuditLogMapper.insert(auditLog) != 1) {
            throw new IllegalStateException(
                    "管理员审计日志写入失败");
        }
    }

    /**
     * 失败审计：委托给独立新事务写入器。
     */
    @Override
    public void recordFailure(
            String action,
            String targetType,
            String targetId,
            Throwable throwable
    ) {
        AdminAuditLog auditLog = buildCommonAuditLog();
        auditLog.setAction(action);
        auditLog.setTargetType(targetType);
        auditLog.setTargetId(targetId);
        auditLog.setResultStatus("FAILED");
        auditLog.setErrorCode(
                throwable != null
                        ? throwable.getClass().getSimpleName()
                        : "UNKNOWN"
        );
        auditLog.setErrorMessage(
                limitErrorMessage(
                        throwable != null
                                ? throwable.getMessage()
                                : "未知异常"
                )
        );

        try {
            adminAuditFailureWriter.writeFailed(auditLog);
        } catch (Exception auditException) {
            /*
             * 失败审计本身失败时记录严重日志，
             * 但不能覆盖最初的业务异常。
             */
            log.error(
                    "管理员失败审计写入异常",
                    auditException
            );
        }
    }

    /**
     * 构建公共审计日志字段：
     * 操作人、角色快照、请求信息、requestId、客户端信息。
     */
    private AdminAuditLog buildCommonAuditLog() {
        AdminAuditLog auditLog = new AdminAuditLog();
        auditLog.setCreatedAt(LocalDateTime.now());

        // 1. 从 SecurityContext 取操作人和角色快照
        AuthenticatedUser operator =
                resolveCurrentAuthenticatedUser();
        if (operator != null) {
            auditLog.setOperatorId(operator.getUserId());
            auditLog.setOperatorRoles(
                    joinRoles(operator.getRoles())
            );
        }

        // 2. 从当前请求取 requestId 和客户端信息
        HttpServletRequest request = currentRequest();
        if (request != null) {
            auditLog.setRequestId(resolveRequestId(request));
            auditLog.setHttpMethod(request.getMethod());
            auditLog.setRequestPath(request.getRequestURI());
            auditLog.setClientIp(resolveClientIp(request));
            auditLog.setUserAgent(
                    limitLength(
                            request.getHeader("User-Agent"),
                            ERROR_MESSAGE_MAX_LENGTH
                    )
            );
        }

        return auditLog;
    }

    /**
     * 从SecurityContext读取当前认证用户。
     * 审计可能在内部线程触发，因此允许为空。
     */
    private AuthenticatedUser resolveCurrentAuthenticatedUser() {
        Authentication authentication =
                SecurityContextHolder
                        .getContext()
                        .getAuthentication();

        if (authentication != null
                && authentication.getPrincipal()
                instanceof AuthenticatedUser authenticatedUser) {
            return authenticatedUser;
        }
        return null;
    }

    /**
     * 角色快照：排序后使用英文逗号连接。
     */
    private String joinRoles(
            java.util.Set<String> roles
    ) {
        if (roles == null || roles.isEmpty()) {
            return "";
        }
        List<String> sortedRoles =
                new ArrayList<>(roles);
        Collections.sort(sortedRoles);
        return String.join(",", sortedRoles);
    }

    /**
     * 读取当前HTTP请求。
     * 内部线程调用时可能没有请求上下文，允许返回null。
     */
    private HttpServletRequest currentRequest() {
        ServletRequestAttributes attributes =
                (ServletRequestAttributes) RequestContextHolder
                        .getRequestAttributes();
        return attributes != null
                ? attributes.getRequest()
                : null;
    }

    /**
     * requestId：优先读取X-Request-Id请求头，否则生成UUID。
     */
    private String resolveRequestId(
            HttpServletRequest request
    ) {
        String requestId = request.getHeader("X-Request-Id");
        return StringUtils.hasText(requestId)
                ? requestId
                : UUID.randomUUID().toString();
    }

    /**
     * 客户端IP：优先信任反向代理转发头，否则取remoteAddr。
     *
     * 注意：只读取可信代理配置下的X-Forwarded-For，
     * 实际项目中应与代理配置配套，这里做基础处理。
     */
    private String resolveClientIp(
            HttpServletRequest request
    ) {
        String forwarded = request.getHeader(
                "X-Forwarded-For"
        );
        if (StringUtils.hasText(forwarded)) {
            // X-Forwarded-For可能是逗号分隔的代理链，取第一个
            int commaIndex = forwarded.indexOf(',');
            return (commaIndex > 0
                    ? forwarded.substring(0, commaIndex)
                    : forwarded).trim();
        }
        return request.getRemoteAddr();
    }

    /**
     * 摘要限长，避免超长正文进入审计表。
     */
    private String limitSummary(String value) {
        return limitLength(value, SUMMARY_MAX_LENGTH);
    }

    /**
     * 错误信息限长。
     */
    private String limitErrorMessage(String value) {
        return limitLength(value, ERROR_MESSAGE_MAX_LENGTH);
    }

    private String limitLength(
            String value,
            int maxLength
    ) {
        if (value == null) {
            return null;
        }
        return value.length() > maxLength
                ? value.substring(0, maxLength)
                : value;
    }
}
