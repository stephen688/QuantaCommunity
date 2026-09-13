package com.quanta.demo0.vo;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 通知视图对象 - 返回给前端的通知信息
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
     */
    private Integer isRead;

    /**
     * 创建时间
     */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createTime;
}