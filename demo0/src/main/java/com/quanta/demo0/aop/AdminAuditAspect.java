package com.quanta.demo0.aop;

import com.quanta.demo0.annotation.AdminAudit;
import com.quanta.demo0.service.AdminAuditRecorder;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.expression.MethodBasedEvaluationContext;
import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.core.ParameterNameDiscoverer;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.stereotype.Component;

/**
 * 管理员高风险操作失败审计切面。
 *
 * 只负责失败记录；成功记录由业务事务中的
 * AdminAuditRecorder显式写入。
 */
@Aspect
@Component
@Slf4j
public class AdminAuditAspect {

    private final ExpressionParser parser =
            new SpelExpressionParser();

    private final ParameterNameDiscoverer
            paramNameDiscoverer =
            new DefaultParameterNameDiscoverer();

    @Autowired
    private AdminAuditRecorder adminAuditRecorder;

    @Around("@annotation(adminAudit)")
    public Object auditAdminAction(
            ProceedingJoinPoint joinPoint,
            AdminAudit adminAudit
    ) throws Throwable {
        try {
            return joinPoint.proceed();
        } catch (Throwable throwable) {
            try {
                adminAuditRecorder.recordFailure(
                        adminAudit.action(),
                        adminAudit.targetType(),
                        resolveTargetId(joinPoint, adminAudit.targetId()),
                        throwable
                );
            } catch (Exception auditException) {
                /*
                 * 失败审计本身失败时记录严重日志，
                 * 但不能覆盖最初的业务异常。
                 */
                log.error("管理员失败审计写入异常", auditException);
            }
            throw throwable;
        }
    }

    /**
     * 解析SpEL目标ID表达式，例如#userId。
     */
    private String resolveTargetId(
            ProceedingJoinPoint joinPoint,
            String targetIdExpression
    ) {
        if (targetIdExpression == null
                || targetIdExpression.isBlank()) {
            return null;
        }

        try {
            MethodSignature signature =
                    (MethodSignature) joinPoint.getSignature();

            MethodBasedEvaluationContext context =
                    new MethodBasedEvaluationContext(
                            joinPoint.getTarget(),
                            signature.getMethod(),
                            joinPoint.getArgs(),
                            paramNameDiscoverer
                    );

            Object value = parser
                    .parseExpression(targetIdExpression)
                    .getValue(context);

            return value != null
                    ? String.valueOf(value)
                    : null;
        } catch (Exception expressionException) {
            log.warn(
                    "解析审计目标ID失败，targetId={}",
                    targetIdExpression,
                    expressionException
            );
            return null;
        }
    }
}
