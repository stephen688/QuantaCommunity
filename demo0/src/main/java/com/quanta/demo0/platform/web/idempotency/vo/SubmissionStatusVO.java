package com.quanta.demo0.platform.web.idempotency.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 提交凭证恢复查询响应。
 *
 * 职责：表达主库能确认的状态和首次响应；边界：UNCONFIRMED 不代表业务已失败。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SubmissionStatusVO {

    private String scene;
    private String status;
    private Object data;
    private LocalDateTime expiresAt;
}
