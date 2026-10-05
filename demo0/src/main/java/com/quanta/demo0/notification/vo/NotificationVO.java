package com.quanta.demo0.notification.vo;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 通知视图对象 - 返回给前端的通知信息
 *
 * 【两个出口共用同一个 VO】HTTP 列表（NotificationServiceImpl 分页查询转换）
 * 和 WebSocket 实时推送（NotificationConsumer.pushWebSocketBestEffort）都
 * 组装它，保证用户从两个渠道看到的通知结构一致。
 * 与实体的差别：去掉收件人/逻辑删除等内部字段，补了 typeDesc 展示字段，
 * createTime 按 yyyy-MM-dd HH:mm:ss 格式化后输出。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class NotificationVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 通知ID
     */
    private Long id;

    /**
     * 触发者用户ID（系统通知可为空）
     */
    private Long actorUserId;

    /**
     * 通知类型（如 COMMENT_ON_CONTENT、LIKE_CONTENT 等）
     */
    private String type;

    /**
     * 通知类型描述（如"评论了你的内容"）
     *
     * 服务端按 NotificationType 枚举翻译，前端不维护文案表；
     * 未知类型降级为"未知通知"（列表）或空串（推送）。
     */
    private String typeDesc;

    /**
     * 通知摘要内容
     */
    private String content;

    /**
     * 扩展字段JSON（关联ID、审核结果等）
     */
    private String payload;

    /**
     * 是否已读：0-未读 1-已读
     *
     * 列表场景来自实体；推送场景恒为 0（新通知必然未读）。
     */
    private Integer isRead;

    /**
     * 创建时间
     */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createTime;
}