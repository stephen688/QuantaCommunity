package com.quanta.demo0.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 通知实体类
 * 对应数据库表：tb_notification
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
     */
    private String payload;

    /**
     * 是否已读：0-未读 1-已读
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