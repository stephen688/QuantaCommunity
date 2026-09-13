package com.quanta.demo0.constant;

/**
 * 管理端业务权限常量。
 *
 * 权限表示用户能够执行的具体操作。
 */
public final class PermissionConstants {

    /**
     * 工具类不允许创建对象。
     */
    private PermissionConstants() {
    }

    /**
     * 查看管理端帖子、回答、评论和举报。
     */
    public static final String CONTENT_READ_ADMIN =
            "CONTENT_READ_ADMIN";

    /**
     * 审核帖子、回答、评论和举报。
     */
    public static final String CONTENT_AUDIT =
            "CONTENT_AUDIT";

    /**
     * 管理员删除内容、回答或评论。
     */
    public static final String CONTENT_DELETE =
            "CONTENT_DELETE";

    /**
     * 审核校友身份认证。
     */
    public static final String IDENTITY_AUDIT =
            "IDENTITY_AUDIT";

    /**
     * 查看管理端用户信息。
     */
    public static final String USER_READ_ADMIN =
            "USER_READ_ADMIN";

    /**
     * 封禁或解封用户。
     */
    public static final String USER_BAN =
            "USER_BAN";

    /**
     * 查看Outbox、Inbox和事件概览。
     */
    public static final String EVENT_READ =
            "EVENT_READ";

    /**
     * 人工重放失败事件。
     */
    public static final String EVENT_REPLAY =
            "EVENT_REPLAY";

    /**
     * 查看管理员安全审计日志。
     */
    public static final String AUDIT_LOG_READ =
            "AUDIT_LOG_READ";

    /**
     * 授予或撤销管理员角色。
     */
    public static final String ROLE_MANAGE =
            "ROLE_MANAGE";
}