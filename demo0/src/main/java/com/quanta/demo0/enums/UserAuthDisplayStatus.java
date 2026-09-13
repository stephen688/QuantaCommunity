package com.quanta.demo0.enums;

/**
 * tb_user.auth_status 展示态（与 tb_user_auth.audit_status / {@link AuditStatus} 不同）。
 * <p>
 * 小程序、dev-seed 约定：0 未认证，1 审核中，2 已认证，3 审核不通过。
 */
public enum UserAuthDisplayStatus {
    NONE(0, "未认证"),
    PENDING(1, "审核中"),
    VERIFIED(2, "已认证"),
    REJECTED(3, "审核不通过");

    private final Integer code;
    private final String desc;

    UserAuthDisplayStatus(Integer code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    public Integer getCode() {
        return code;
    }

    public String getDesc() {
        return desc;
    }
}
