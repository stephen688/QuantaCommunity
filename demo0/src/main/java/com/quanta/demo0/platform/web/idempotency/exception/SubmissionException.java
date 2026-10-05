package com.quanta.demo0.platform.web.idempotency.exception;

import com.quanta.demo0.platform.common.exception.BaseException;
import lombok.Getter;

/**
 * HTTP 提交幂等契约异常。
 *
 * 职责：携带 400/409 业务状态码和可选重试等待时间；边界：由全局异常处理器翻译为 Result。
 */
@Getter
public class SubmissionException extends BaseException {

    private final int statusCode;
    private final Long retryAfterSeconds;

    public SubmissionException(int statusCode, String message) {
        this(statusCode, message, null);
    }

    public SubmissionException(int statusCode, String message, Long retryAfterSeconds) {
        super(message);
        this.statusCode = statusCode;
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public static SubmissionException badRequest(String message) {
        return new SubmissionException(400, message);
    }

    public static SubmissionException conflict(String message) {
        return new SubmissionException(409, message);
    }

    public static SubmissionException unconfirmed() {
        return new SubmissionException(409, "提交结果尚未确认，请稍后查询", 2L);
    }

    public static SubmissionException expired() {
        return conflict("提交凭证已过期，请先核对发布记录");
    }
}
