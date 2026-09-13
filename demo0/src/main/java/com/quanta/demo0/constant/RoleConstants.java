package com.quanta.demo0.constant;

/**
 * 系统角色常量。
 *
 * 角色表示用户的身份或岗位，
 * 具体可以执行什么操作由权限映射决定。
 */
public final class RoleConstants {

    /**
     * 工具类不允许创建对象。
     */
    private RoleConstants() {
    }

    /**
     * 已登录普通用户。
     */
    public static final String USER = "USER";

    /**
     * 已通过校友身份认证的用户。
     */
    public static final String VERIFIED_USER = "VERIFIED_USER";

    /**
     * 内容审核人员。
     */
    public static final String CONTENT_AUDITOR =
            "CONTENT_AUDITOR";

    /**
     * 运营管理人员。
     */
    public static final String OPERATIONS_ADMIN =
            "OPERATIONS_ADMIN";

    /**
     * 超级管理员。
     */
    public static final String SUPER_ADMIN =
            "SUPER_ADMIN";
}
