package com.quanta.demo0.enums;

import java.util.Arrays;

public enum AuditStatus {
    UNSUBMITTED(-1, "未提交"),
    PENDING(0, "待审核"),
    APPROVED(1, "已通过"),
    REJECTED(2, "已驳回");

    private final Integer code;
    private final String desc;

    AuditStatus(Integer code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    public Integer getCode() {
        return code;
    }

    public String getDesc() {
        return desc;
    }

    public static boolean isValid(Integer code) {
        return Arrays.stream(values()).anyMatch(e -> e.code.equals(code));
    }

    public static AuditStatus fromCode(Integer code) {
        return Arrays.stream(values())
                .filter(e -> e.code.equals(code))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("非法审核状态: " + code));
    }

    public static String descByCode(Integer code) {
        return Arrays.stream(values())
                .filter(e -> e.code.equals(code))
                .map(AuditStatus::getDesc)
                .findFirst()
                .orElse("未知状态");
    }
}
