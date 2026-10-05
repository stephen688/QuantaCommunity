// d:/download/资料/day01/后端初始工程/demo0/src/main/java/com/quanta/demo0/moderation/model/ModerationResult.java
package com.quanta.demo0.moderation.result;

import com.quanta.demo0.moderation.enums.ModerationDecision;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 审核结果（都是ai回复的）
 * 包含审核决策、驳回原因、风险标签、风险等级、原始响应等信息
 *
 * ============================================================
 * 【decision 四个取值不是从云文档抄来的，是本包代码定义的】
 * ============================================================
 * PASS / REJECT / MANUAL / ERROR 由 {@link ModerationDecision} 枚举定义，
 * 赋值逻辑全部在本包客户端里，读代码时以这里为准：
 * - 文本：AliyunTextModerationClient.mapRiskLevel 把云返回的 riskLevel 映射成决策——
 *   high 看 auto-reject-enabled 开关、medium 看 manual-on-suspect 开关、low 直接 PASS
 *   （application.yml quanta.moderation 两个开关当前均为 true）；
 * - 图片：AliyunImageModerationClient.mapImageResult 按置信度 70 分界
 *   （REJECT_CONFIDENCE=70F），再叠加同样的两个开关；
 * - ERROR 不是业务结论，而是"这一次没审成"（HTTP/code 非 200 或调用抛异常），
 *   消费端会拿它触发重试，见 ModerationWorkflowServiceImpl。
 *
 * 本对象是机审的"结果货币"：两个客户端生产它，ContentModerationServiceImpl
 * 合并双模态结果并落库成 ModerationRecord，ModerationWorkflowServiceImpl
 * 按它的 decision 分发到 content/answer/comment 的审核状态机。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ModerationResult {

    private ModerationDecision decision; // PASS / REJECT / MANUAL / ERROR

    private String rejectReason;         // 驳回原因（仅在 REJECT 决策时有）

    /** 文本端是云返回的 labels 原串包成单元素列表；图片端是各图 label 的合集（nonLabel 已剔除） */
    private List<String> labels;         // 风险标签（如：广告、色情）

    /** 风险等级：目前只有文本客户端填写（云返回 reason JSON 里的 riskLevel，解析失败兜底 "medium"）；图片客户端不填 */
    private String riskLevel;            // 风险等级

    private String rawResponse;          // 云 API 原始响应（用于排查）
}