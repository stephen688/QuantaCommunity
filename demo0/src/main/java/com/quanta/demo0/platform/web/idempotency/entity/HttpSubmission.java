package com.quanta.demo0.platform.web.idempotency.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 主库中的 HTTP 提交凭证。
 *
 * 职责：保存一次成功提交的摘要和首次响应；边界：不保存 JWT、请求头或原始请求体。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class HttpSubmission {

    private Long id;
    private Long userId;
    private String scene;
    private String submissionToken;
    private String requestHash;
    private String status;
    private String responseData;
    private LocalDateTime createTime;
    private LocalDateTime expiresAt;
}
