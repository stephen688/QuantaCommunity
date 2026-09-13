package com.quanta.demo0.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "quanta.outbox.maintenance")
public class OutboxMaintenanceProperties {

    private int retentionDays = 30;

    private long backlogWarningSeconds = 300;
}
