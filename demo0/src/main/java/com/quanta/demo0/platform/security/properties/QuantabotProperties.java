package com.quanta.demo0.platform.security.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * QuantaBot 接入配置（契约 C-5/C-6）。
 *
 * bot 系统账号固定 user_id=10000（与 dev-seed-incremental.sql 种子一致）；
 * 昵称「框框」是 @ 文本检测与前端展示的统一口径。
 */
@Data
@Component
@ConfigurationProperties(prefix = "quantabot")
public class QuantabotProperties {

    /** bot 系统账号 user_id（seed 固定 10000）。 */
    private Long botUserId = 10000L;

    /** bot 昵称（@ 卡片插入文本、mention 文本检测口径）。 */
    private String botNickname = "框框";
}
