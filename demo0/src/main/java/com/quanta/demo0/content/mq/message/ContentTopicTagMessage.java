package com.quanta.demo0.content.mq.message;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 内容主题标签可靠事件消息。
 *
 * 职责：只传递事件标识和帖子标识，消费者始终回读 MySQL 当前内容；
 * 边界：不携带正文，避免 Outbox/Rabbit payload 保存原文和过期内容。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ContentTopicTagMessage implements Serializable {

    private String eventId;
    private String eventType;
    private Long contentId;
    private LocalDateTime occurredAt;
    private Integer retryCount;
}
