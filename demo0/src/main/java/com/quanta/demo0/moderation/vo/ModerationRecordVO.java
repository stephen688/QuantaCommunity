package com.quanta.demo0.moderation.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 管理端 AI 审核记录视图（不含 rawResponse）
 *
 * 由 AdminModerationServiceImpl#toVo 从 ModerationRecord 裁剪而来：
 * **丢掉 rawResponse（云 API 原始报文，动辄几 KB）**——管理端列表/详情只关心结论，
 * 大字段只留在库里供排障，缩小接口响应体。
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class ModerationRecordVO {

    /** CONTENT / ANSWER / COMMENT */
    private String targetType;
    /** 帖子/回答/评论 ID */
    private Long targetId;
    /** 审核服务商，当前固定 ALIYUN */
    private String provider;
    /** 审核决策：PASS / REJECT / MANUAL / ERROR（取值见 ModerationDecision） */
    private String decision;
    /** 风险等级（仅文本审核有：high/medium/low） */
    private String riskLevel;
    /** 风险标签 JSON 数组串，如 ["广告","色情"]（与 ModerationResult.labels 的落库格式一致） */
    private String labels;
    /** 驳回原因（REJECT 时有值） */
    private String rejectReason;
    /** 任务终态：DONE / FAILED */
    private String taskStatus;
    /** 消费重试次数（>0 说明机审曾失败过） */
    private Integer retryCount;
    /** 记录最后更新时间（= 最近一次审核时间） */
    private LocalDateTime updateTime;
}
