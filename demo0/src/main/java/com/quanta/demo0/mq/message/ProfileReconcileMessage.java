package com.quanta.demo0.mq.message;

import lombok.*;
import java.io.Serializable;
import java.time.LocalDateTime;

/** 显式偏好校准信号：仅指向用户当前事实，不携带记忆原文或可被乱序重复累计的增量。 */
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class ProfileReconcileMessage implements Serializable {
    private String eventId;
    private String eventType;
    private Long userId;
    private LocalDateTime occurredAt;
    private Integer retryCount;
}
