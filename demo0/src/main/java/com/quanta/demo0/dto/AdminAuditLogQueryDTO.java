package com.quanta.demo0.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 管理端审计日志分页查询条件。
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class AdminAuditLogQueryDTO {

    @Builder.Default
    private Integer pageNum = 1;

    @Builder.Default
    private Integer pageSize = 10;

    /** 操作管理员ID */
    private Long operatorId;

    /** 操作代码，如 USER_BAN */
    private String action;

    /** 目标类型，如 USER / CONTENT / REPORT */
    private String targetType;

    /** 目标ID */
    private String targetId;

    /** SUCCESS / FAILED */
    private String resultStatus;

    /** 请求关联ID */
    private String requestId;

    /** 起始时间（含） */
    private LocalDateTime startTime;

    /** 结束时间（含） */
    private LocalDateTime endTime;
}
