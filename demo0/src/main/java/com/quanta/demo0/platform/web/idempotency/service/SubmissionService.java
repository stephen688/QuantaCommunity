package com.quanta.demo0.platform.web.idempotency.service;

import com.quanta.demo0.platform.web.idempotency.vo.SubmissionStatusVO;

import java.util.function.Supplier;

/**
 * HTTP 写入口幂等服务。
 *
 * 职责：统一处理凭证校验、重放、Redis 占位和主库结果恢复；边界：业务写入由调用方 Supplier 提供。
 */
public interface SubmissionService {

    /**
     * 执行一次带提交凭证的业务写入；兼容开关关闭时缺失凭证沿用旧调用路径。
     *
     * @param scene 服务端固定场景
     * @param token Idempotency-Key，可为空（仅兼容模式）
     * @param request 领域请求 DTO
     * @param responseType 首次响应 data 类型
     * @param operation 现有领域 Service 写入操作
     * @param <T> 响应类型
     * @return 首次执行或重放的相同响应
     */
    <T> T execute(String scene, String token, Object request,
                  Class<T> responseType, Supplier<T> operation);

    /** 查询当前认证用户自己的提交状态。 */
    SubmissionStatusVO query(String scene, String token);
}
