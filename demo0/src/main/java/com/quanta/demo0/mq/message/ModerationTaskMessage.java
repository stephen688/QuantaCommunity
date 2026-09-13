// d:/download/资料/day01/后端初始工程/demo0/src/main/java/com/quanta/demo0/mq/message/ModerationTaskMessage.java
package com.quanta.demo0.mq.message;

import com.quanta.demo0.annotation.ModerationTargetType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ModerationTaskMessage implements Serializable {
    private static final long serialVersionUID = 1L;


    /**
     * Outbox 生成的唯一事件 ID。
     *
     * 同一事件即使被发送两次，eventId 也保持不变。
     */
    private String eventId;

    /**
     * 当前固定为 MODERATION_REQUESTED。
     */
    private String eventType;

    /**
     * 业务事件真正发生的时间。
     */
    private LocalDateTime occurredAt;


    private ModerationTargetType targetType; // CONTENT / ANSWER / COMMENT
    private Long targetId;                   // 帖子/回答/评论 ID
    private Long publisherUserId;            // 发布者 ID
    private String title;                    // 标题（仅帖子）
    private String content;                  // 正文
    private List<String> imageUrls;          // 图片 URL 列表
    private LocalDateTime createdAt;
    private Integer retryCount;              // 重试次数
}