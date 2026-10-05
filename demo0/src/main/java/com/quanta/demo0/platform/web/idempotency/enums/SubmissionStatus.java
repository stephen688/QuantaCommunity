package com.quanta.demo0.platform.web.idempotency.enums;

/** 提交凭证在主库中的状态以及恢复查询对外状态。 */
public final class SubmissionStatus {

    public static final String PROCESSING = "PROCESSING";
    public static final String SUCCEEDED = "SUCCEEDED";
    public static final String UNCONFIRMED = "UNCONFIRMED";
    public static final String EXPIRED = "EXPIRED";

    private SubmissionStatus() {
    }
}
