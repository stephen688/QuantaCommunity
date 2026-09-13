-- =============================================================================
-- V_ai_moderation.sql — AI 云审核记录表（可重复执行）
--
-- 用法:
--   mysql --host=127.0.0.1 --port=3306 --user=root -p --default-character-set=utf8mb4 demo < src/main/resources/db/V_ai_moderation.sql
--
-- 说明:
--   - 采用「审核完再写库」策略，不在 PROCESSING 中间态落库
--   - 唯一索引 (target_type, target_id, provider) 保证 MQ 幂等
-- =============================================================================

SET NAMES utf8mb4;

CREATE TABLE IF NOT EXISTS `tb_moderation_record` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
    `target_type` VARCHAR(20) NOT NULL COMMENT '目标类型：CONTENT/ANSWER/COMMENT',
    `target_id` BIGINT NOT NULL COMMENT '业务 ID',
    `provider` VARCHAR(20) NOT NULL DEFAULT 'ALIYUN' COMMENT '审核服务商：ALIYUN/LOCAL_CONFIG',
    `decision` VARCHAR(20) NOT NULL COMMENT '审核决策：PASS/REJECT/MANUAL/ERROR',
    `risk_level` VARCHAR(20) DEFAULT NULL COMMENT '风险等级',
    `labels` JSON DEFAULT NULL COMMENT '风险标签',
    `reject_reason` VARCHAR(500) DEFAULT NULL COMMENT '驳回原因',
    `raw_response` MEDIUMTEXT DEFAULT NULL COMMENT '云 API 原始响应',
    `content_fingerprint` VARCHAR(64) DEFAULT NULL COMMENT '内容指纹 (MD5)',
    `task_status` VARCHAR(20) NOT NULL DEFAULT 'DONE' COMMENT '任务状态：DONE/FAILED',
    `retry_count` INT NOT NULL DEFAULT 0 COMMENT '重试次数',
    `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_target_provider` (`target_type`, `target_id`, `provider`),
    KEY `idx_task_status_update_time` (`task_status`, `update_time`) COMMENT '运维查询索引'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI 审核记录表';
