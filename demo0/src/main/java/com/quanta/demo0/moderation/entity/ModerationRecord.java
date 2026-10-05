// d:/download/资料/day01/后端初始工程/demo0/src/main/java/com/quanta/demo0/entity/ModerationRecord.java
package com.quanta.demo0.moderation.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * AI 审核记录实体类
 *
 * 对应表 tb_moderation_record（见 db/V_ai_moderation.sql），是"机器审了什么、怎么判的"
 * 的唯一存档：管理端查询（AdminModerationService）、消费端指纹去重（ContentModerationServiceImpl）
 * 都以它为准。
 *
 * ============================================================
 * 【为什么按 (targetType, targetId, provider) 覆盖写，而不是每次审核插一行？】
 * ============================================================
 * 表上有唯一索引 uk_target_provider，Mapper 用 INSERT ... ON DUPLICATE KEY UPDATE
 * 幂等写入——**一条内容只保留最新一次审核结论**：
 * - "查最新记录"退化成一次等值查询（selectLatest），管理端秒开；
 * - contentFingerprint（内容 MD5）配合 taskStatus=DONE 判断"内容没变就不再送审"，省云调用费用。
 * 【审计追溯】rawResponse 保留云 API 原始报文（MEDIUMTEXT），有争议时可回放当时的机器判断依据。
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
    /** 内容指纹（标题+正文+图片 URL 排序后拼接的 MD5）。与 taskStatus=DONE 配合实现"内容没变不重审" */
    private String contentFingerprint; // 内容指纹


    // 修改字段类型/注释
    /**
     * 任务终态：DONE=审核完成（指纹去重只认它）；FAILED=重试耗尽仍失败
     * （由 ContentModerationServiceImpl#saveFailedRecord 写入，供运维排查）
     */
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