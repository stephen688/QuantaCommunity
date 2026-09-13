-- =============================================================================
-- V_outbox.sql — Outbox / Inbox 可靠事件链路基础表
--
-- 第 1 步只创建数据库结构，不接入任何业务代码。
--
-- Outbox：业务数据提交时，同时保存一条待发送事件。
-- Inbox ：消费者处理事件时，记录处理状态，防止同一消费者重复执行。
--
-- 目标数据库：MySQL 8.0+
-- 可重复执行：CREATE TABLE IF NOT EXISTS
-- =============================================================================

SET NAMES utf8mb4;

CREATE TABLE IF NOT EXISTS `tb_outbox_event` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '数据库主键',
    `event_id` CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '事件唯一 ID，UUID',
    `event_type` VARCHAR(64) NOT NULL COMMENT '事件类型，例如 MODERATION_REQUESTED',
    `aggregate_type` VARCHAR(64) NOT NULL COMMENT '业务对象类型，例如 CONTENT',
    `aggregate_id` BIGINT NOT NULL COMMENT '业务对象 ID',
    `payload` JSON NOT NULL COMMENT '投递所需的最小 JSON 消息',
    `status` VARCHAR(20) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/PROCESSING/SENT/DEAD',
    `retry_count` INT NOT NULL DEFAULT 0 COMMENT '自动重试次数',
    `next_retry_time` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '下一次允许投递时间',
    `locked_by` VARCHAR(128) DEFAULT NULL COMMENT '当前处理实例的租约所有权令牌',
    `locked_until` DATETIME(3) DEFAULT NULL COMMENT '租约过期时间',
    `last_error` VARCHAR(2000) DEFAULT NULL COMMENT '最后一次失败原因，不保存完整异常堆栈',
    `replay_count` INT NOT NULL DEFAULT 0 COMMENT '人工重放次数',
    `last_replay_by` BIGINT DEFAULT NULL COMMENT '最后一次重放的管理员 ID',
    `last_replay_time` DATETIME(3) DEFAULT NULL COMMENT '最后一次人工重放时间',
    `create_time` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
    `update_time` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
    `sent_time` DATETIME(3) DEFAULT NULL COMMENT '确认发送成功时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_outbox_event_id` (`event_id`),
    KEY `idx_outbox_scan` (`status`, `next_retry_time`, `create_time`),
    KEY `idx_outbox_lease_recovery` (`status`, `locked_until`, `create_time`),
    KEY `idx_outbox_aggregate` (`aggregate_type`, `aggregate_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='可靠事件发件箱';

CREATE TABLE IF NOT EXISTS `tb_inbox_event` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '数据库主键',
    `event_id` CHAR(36) CHARACTER SET ascii COLLATE ascii_bin DEFAULT NULL COMMENT '消息中的事件 ID，迁移期间允许旧消息为空',
    `consumer_name` VARCHAR(128) NOT NULL COMMENT '消费者名称，例如 moderation-consumer',
    `outbox_event_id` BIGINT DEFAULT NULL COMMENT '对应的 Outbox 数据库主键',
    `event_type` VARCHAR(64) DEFAULT NULL COMMENT '事件类型',
    `aggregate_type` VARCHAR(64) DEFAULT NULL COMMENT '业务对象类型',
    `aggregate_id` BIGINT DEFAULT NULL COMMENT '业务对象 ID',
    `status` VARCHAR(20) NOT NULL DEFAULT 'PROCESSING' COMMENT 'PROCESSING/RETRYING/SUCCESS/DEAD',
    `retry_count` INT NOT NULL DEFAULT 0 COMMENT '消费重试次数',
    `locked_by` VARCHAR(128) DEFAULT NULL COMMENT '当前处理实例的租约所有权令牌',
    `locked_until` DATETIME(3) DEFAULT NULL COMMENT '租约过期时间',
    `last_error` VARCHAR(2000) DEFAULT NULL COMMENT '最后一次失败原因，不保存完整异常堆栈',
    `replay_count` INT NOT NULL DEFAULT 0 COMMENT '人工重放次数',
    `last_replay_by` BIGINT DEFAULT NULL COMMENT '最后一次重放的管理员 ID',
    `last_replay_time` DATETIME(3) DEFAULT NULL COMMENT '最后一次人工重放时间',
    `create_time` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
    `update_time` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
    `processed_time` DATETIME(3) DEFAULT NULL COMMENT '成功或最终失败的处理时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_inbox_consumer_event` (`consumer_name`, `event_id`),
    KEY `idx_inbox_status_lease` (`status`, `locked_until`, `create_time`),
    KEY `idx_inbox_outbox_event` (`outbox_event_id`),
    KEY `idx_inbox_aggregate` (`aggregate_type`, `aggregate_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='可靠事件收件箱';
