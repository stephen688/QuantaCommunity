package com.quanta.demo0.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 开启 Spring 定时任务。
 * Outbox 发送器会定时扫描 PENDING 事件。
 */
@Configuration
@EnableScheduling
public class OutboxSchedulingConfig {
}