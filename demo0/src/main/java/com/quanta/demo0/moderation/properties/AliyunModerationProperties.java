// d:/download/资料/day01/后端初始工程/demo0/src/main/java/com/quanta/demo0/properties/AliyunModerationProperties.java
package com.quanta.demo0.moderation.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 审核域配置：绑定 application.yml 的 quanta.moderation 配置块
 * （enabled / text-enabled / image-enabled / auto-reject-enabled / manual-on-suspect /
 *  max-retry-count=3 / cost-control-enabled / targets.content|answer|comment）。
 *
 * ============================================================
 * 【为什么开关要做成分层的？】
 * ============================================================
 * 总开关 enabled → 模态开关 text/image-enabled → 按目标类型的
 * targets.content/answer/comment，三层任一关掉都不送阿里云；
 * 目标开关关掉后的"去向"由 disabledPolicy 决定（APPROVED 直接过 / PENDING 转人工）——
 * 比如评论默认关机审省费用，但配 APPROVED 让评论照常发布，不因省钱而堵住发布链路。
 *
 * 这些字段的消费点（都已核实）：
 * - enabled / textEnabled / imageEnabled / targets.*：ContentModerationServiceImpl（编排层）；
 * - autoRejectEnabled / manualOnSuspect：两个 Aliyun*Client（机审结论 → 决策的翻译层）；
 * - maxRetryCount：ModerationWorkflowServiceImpl（消费重试上限，超限转 DEAD）。
 * 【注意】costControlEnabled 目前没有任何代码消费点——真正起省钱作用的是
 * 内容指纹去重（见 ContentModerationServiceImpl#doModerate）；regionId 也只是
 * 声明性配置，建连实际用的是 endpoint。
 */
@Data
@Component
@ConfigurationProperties(prefix = "quanta.moderation")
public class AliyunModerationProperties {
    private boolean enabled;// 是否开启
    private boolean textEnabled;// 是否开启文本审核
    private boolean imageEnabled;// 是否开启图片审核
    private String regionId;// 区域ID
    private String endpoint;// API地址
    private String accessKeyId;// 访问密钥ID
    private String accessKeySecret;// 访问密钥
    private boolean autoRejectEnabled;// 是否开启自动拒绝（即让ai直接拒绝，不进行手动审核）
    private boolean manualOnSuspect;// 是否开启手动审核
    private int maxRetryCount;// 最大重试次数
    private boolean costControlEnabled;// 是否开启成本控制

    /** 目标配置：content/answer/comment 三个键与 ModerationTargetType 一一对应 */
    private Targets targets;// 目标配置：内容、回答、评论

    /** 按目标类型分组的审核开关；yml 缺配某类型时 getTargetConfig 返回 null，按 PENDING 兜底 */
    @Data
    public static class Targets {
        private TargetConfig content;
        private TargetConfig answer;
        private TargetConfig comment;
    }

    /** 单个目标类型的开关组合：enabled 决定是否机审，disabledPolicy 决定关机审后走哪条兜底路 */
    @Data
    public static class TargetConfig {
        private boolean enabled;// 是否开启
        private String disabledPolicy;// 禁用策略：APPROVED 或 PENDING
    }
}