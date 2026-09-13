// d:/download/资料/day01/后端初始工程/demo0/src/main/java/com/quanta/demo0/entity/ModerationRecord.java
package com.quanta.demo0.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * AI 审核记录实体类
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class ModerationRecord implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 主键 ID
     */
    private Long id;

    /**
     * 目标类型：CONTENT / ANSWER / COMMENT
     */
    private String targetType;

    /**
     * 业务 ID（帖子/回答/评论 ID）
     */
    private Long targetId;

    /**
     * 审核服务商：ALIYUN
     */
    private String provider;

    /**
     * 审核决策：PASS / REJECT / MANUAL / ERROR
     */
    private String decision;

    /**
     * 风险等级
     */
    private String riskLevel;

    /**
     * 风险标签（JSON 数组字符串）
     */
    private String labels;

    /**
     * 驳回原因
     */
    private String rejectReason;

    /**
     * 云 API 原始响应（JSON 字符串）
     */
    private String rawResponse; // 对应 MEDIUMTEXT

    // 新增字段
    private String contentFingerprint; // 内容指纹


    // 修改字段类型/注释
    private String taskStatus; // 仅 DONE/FAILED

    /**
     * 重试次数
     */
    private Integer retryCount;

    /**
     * 创建时间
     */
    private LocalDateTime createTime;

    /**
     * 更新时间
     */
    private LocalDateTime updateTime;
}