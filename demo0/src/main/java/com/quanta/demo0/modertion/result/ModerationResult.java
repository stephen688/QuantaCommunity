// d:/download/资料/day01/后端初始工程/demo0/src/main/java/com/quanta/demo0/moderation/model/ModerationResult.java
package com.quanta.demo0.modertion.result;

import com.quanta.demo0.annotation.ModerationDecision;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 审核结果（都是ai回复的）
 * 包含审核决策、驳回原因、风险标签、风险等级、原始响应等信息
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ModerationResult {

    private ModerationDecision decision; // PASS / REJECT / MANUAL / ERROR

    private String rejectReason;         // 驳回原因（仅在 REJECT 决策时有）

    private List<String> labels;         // 风险标签（如：广告、色情）

    private String riskLevel;            // 风险等级

    private String rawResponse;          // 云 API 原始响应（用于排查）
}