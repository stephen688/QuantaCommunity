package com.quanta.demo0.identity.enums;
import com.quanta.demo0.platform.common.enums.AuditStatus;


/**
 * tb_user.auth_status 展示态（与 tb_user_auth.audit_status / {@link AuditStatus} 不同）。
 * <p>
 * 小程序、dev-seed 约定：0 未认证，1 审核中，2 已认证，3 审核不通过。
 */

/**
 * 认证的"展示态"枚举——存的是 tb_user.auth_status 列。
 *
 * ============================================================
 * 【为什么同一件事要有两套状态码？】
 * ============================================================
 * tb_user_auth.audit_status 是审核"事实"（{@link AuditStatus}：-1 未提交 / 0 待审核 /
 * 1 已通过 / 2 已驳回），归 identity 域管；tb_user.auth_status 是给小程序"我的"页
 * 直接读的"投影"（0/1/2/3），冗余在用户表上，避免前端每次都去联认证表。
 * **两套码值刻意不同且不可互换**：audit_status 的 0 是"待审核"，auth_status 的 0
 * 却是"未认证"。历史上两套码曾被写混（db/fix_user_auth_status_display.sql 就是
 * 专门修这个的脚本），所以读写都必须经过枚举而不是裸数字。
 *
 * 事实 → 展示态的映射关系分散在三处写点，口径一致：
 * 提交/重提 PENDING(1)（IdentityServiceImpl#addUserAuth）、
 * 审核结果 VERIFIED(2)/REJECTED(3)（IdentityExamServiceImpl#audit）、
 * 不一致自愈（UserProfileServiceImpl#resolveAuthDisplayStatus）。
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
