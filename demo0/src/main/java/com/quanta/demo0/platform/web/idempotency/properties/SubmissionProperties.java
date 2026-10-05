package com.quanta.demo0.platform.web.idempotency.properties;

import lombok.Data;
import jakarta.annotation.PostConstruct;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * HTTP 提交幂等配置。
 *
 * 职责：提供部署兼容开关和凭证生命周期参数；边界：不承载 Redis、数据库或领域业务。
 */
@Data
@Component
@ConfigurationProperties(prefix = "quanta.submission-idempotency")
public class SubmissionProperties {

    /** 后端切换普通用户必须携带 Idempotency-Key 的开关，默认兼容旧客户端。 */
    private boolean requireUserKey = false;

    /** Redis 并发占位的短租期；数据库唯一键是最终防线。 */
    private long processingTtlSeconds = 30L;

    /** 成功提交凭证保存时间。 */
    private long retentionDays = 7L;

    /** 每次清理最多删除的已完成过期凭证。 */
    private int cleanupBatchSize = 500;

    /** 防止幂等凭证把异常大的响应写进 JSON 列。 */
    private int maxResponseBytes = 256 * 1024;

    /** 启动期校验生命周期参数，避免清理任务或 NX 占位使用无效 TTL。 */
    @PostConstruct
    public void validate() {
        if (processingTtlSeconds <= 0 || retentionDays <= 0
                || cleanupBatchSize <= 0 || maxResponseBytes <= 0) {
            throw new IllegalStateException("quanta.submission-idempotency 参数必须为正数");
        }
    }
}
