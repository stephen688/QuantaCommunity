// d:/download/资料/day01/后端初始工程/demo0/src/main/java/com/quanta/demo0/enums/ModerationDisabledPolicy.java
package com.quanta.demo0.moderation.policy;

/**
 * 关闭某类内容的 AI 机审后的兜底策略，对应 yml 的
 * quanta.moderation.targets.*.disabled-policy 取值。
 *
 * 【注意】配置绑定侧 TargetConfig.disabledPolicy 是 String，ContentModerationServiceImpl
 * 用 "APPROVED".equalsIgnoreCase(policy) 比较——本枚举实际承担"合法取值清单"的文档角色。
 */
public enum ModerationDisabledPolicy {
    APPROVED,  // 关闭 AI 时，敏感词通过后直接通过
    PENDING    // 关闭 AI 时，保留待审交给人工审核
}