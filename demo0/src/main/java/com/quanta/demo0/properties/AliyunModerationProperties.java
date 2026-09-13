// d:/download/资料/day01/后端初始工程/demo0/src/main/java/com/quanta/demo0/properties/AliyunModerationProperties.java
package com.quanta.demo0.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

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

    private Targets targets;// 目标配置：内容、回答、评论

    @Data
    public static class Targets {
        private TargetConfig content;
        private TargetConfig answer;
        private TargetConfig comment;
    }

    @Data
    public static class TargetConfig {
        private boolean enabled;// 是否开启
        private String disabledPolicy;// 禁用策略：APPROVED 或 PENDING
    }
}