package com.quanta.demo0.notification.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 通知实体类
 * 对应数据库表：tb_notification
 *
 * 一行 = 一个收件人的一条通知。写入方只有一个：NotificationConsumeServiceImpl
 * 在消费事务里 insert（消息的传输形态是 NotificationEventMessage，落库时才
 * 转成实体）；读侧查询与已读更新走 NotificationMapper。
 *
 * ============================================================
 * 【为什么逻辑删除而不物理删除通知？】
 * ============================================================
 * 通知是"已发生事实"的记录，用户删掉的只是自己的可见性；保留行便于
 * 审计排查（谁在何时给谁发了什么），读侧 SQL 统一带 is_deleted=0 过滤
 * 即可对用户隐藏。当前代码里没有删除入口（Controller 只暴露查询和已读），
 * 字段先落位备用。
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class Notification implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 通知ID（主键）
     */
    private Long id;

    /**
     * 接收人用户ID
     */
    private Long recipientUserId;

    /**
     * 触发者用户ID（系统通知可为空）
     *
     * 谁能写：消费者，取自消息 actorUserId；审核/认证类通知以系统名义发送
     * 时生产方直接置 null（如 IdentityExamServiceImpl 的审核通知）。
     */
    private Long actorUserId;

    /**
     * 通知类型
     */
    private String type;

    /**
     * 通知摘要内容
     */
    private String content;

    /**
     * 扩展字段JSON（关联ID、审核结果等）
     *
     * 存的是消息 payload 序列化后的 JSON 文本（Map → JSON 字符串），
     * 读侧不解析、原样透传给前端，前端解析后用于跳转。
     */
    private String payload;

    /**
     * 是否已读：0-未读 1-已读
     *
     * 只有收件人本人能改：updateIsRead 的 UPDATE 带 recipient_user_id 条件。
     * 未读数 = count(is_read=0)，见 NotificationMapper.countUnreadByUserId。
     */
    private Integer isRead;

    /**
     * 创建时间
     */
    private LocalDateTime createTime;

    /**
     * 更新时间
     */
    private LocalDateTime updateTime;

    /**
     * 逻辑删除：0-未删除 1-已删除
     */
    private Integer isDeleted;
}