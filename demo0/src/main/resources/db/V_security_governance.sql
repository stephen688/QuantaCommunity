-- =============================================================================
-- V_security_governance.sql
--
-- 安全治理基础表。
--
-- 已完成：
-- 1. 创建用户角色关联表 user_role；
-- 2. 将现有 is_admin = 1 的用户迁移为 SUPER_ADMIN；
-- 3. 暂时保留 tb_user.is_admin 兼容字段；
-- 4. 创建管理员高风险操作审计日志表 admin_audit_log。
--
-- 目标数据库：MySQL 8.0+
-- 可重复执行：是
-- =============================================================================

SET NAMES utf8mb4;

CREATE TABLE IF NOT EXISTS `user_role` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '数据库主键',
    `user_id` BIGINT NOT NULL COMMENT '拥有角色的用户ID',
    `role_code` VARCHAR(64)
        CHARACTER SET ascii
        COLLATE ascii_bin
        NOT NULL COMMENT '角色代码，例如SUPER_ADMIN',
    `created_by` BIGINT DEFAULT NULL COMMENT '授予角色的管理员ID，系统迁移时为空',
    `created_at` DATETIME(3)
        NOT NULL
        DEFAULT CURRENT_TIMESTAMP(3)
        COMMENT '角色授予时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_user_role` (`user_id`, `role_code`),
    KEY `idx_user_role_role` (`role_code`, `user_id`)
) ENGINE=InnoDB
  DEFAULT CHARSET=utf8mb4
  COMMENT='用户管理角色关联表';

-- 将原有管理员幂等迁移为 SUPER_ADMIN，保证升级后原管理权限不丢失。
INSERT INTO `user_role` (
    `user_id`,
    `role_code`,
    `created_by`
)
SELECT
    user_table.`id`,
    'SUPER_ADMIN',
    NULL
FROM `tb_user` user_table
WHERE COALESCE(user_table.`is_admin`, 0) = 1
  AND COALESCE(user_table.`is_deleted`, 0) = 0
  AND NOT EXISTS (
      SELECT 1
      FROM `user_role` existing_role
      WHERE existing_role.`user_id` = user_table.`id`
        AND existing_role.`role_code` = 'SUPER_ADMIN'
  );

-- =============================================================================
-- 管理员高风险操作审计日志表
-- =============================================================================
CREATE TABLE IF NOT EXISTS `admin_audit_log` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '审计日志ID',
    `request_id` VARCHAR(64) NOT NULL COMMENT '请求关联ID',
    `operator_id` BIGINT NOT NULL COMMENT '操作管理员ID',
    `operator_roles` VARCHAR(512) NOT NULL COMMENT '操作时角色快照',
    `action` VARCHAR(64) NOT NULL COMMENT '操作代码',
    `target_type` VARCHAR(64) NOT NULL COMMENT '目标类型',
    `target_id` VARCHAR(128) DEFAULT NULL COMMENT '目标ID',
    `http_method` VARCHAR(16) DEFAULT NULL COMMENT 'HTTP方法',
    `request_path` VARCHAR(512) DEFAULT NULL COMMENT '请求路径',
    `before_summary` TEXT DEFAULT NULL COMMENT '操作前脱敏摘要',
    `after_summary` TEXT DEFAULT NULL COMMENT '操作后脱敏摘要',
    `result_status` VARCHAR(16) NOT NULL COMMENT 'SUCCESS或FAILED',
    `error_code` VARCHAR(64) DEFAULT NULL COMMENT '失败错误码',
    `error_message` VARCHAR(1000) DEFAULT NULL COMMENT '脱敏失败原因',
    `client_ip` VARCHAR(64) DEFAULT NULL COMMENT '客户端IP',
    `user_agent` VARCHAR(512) DEFAULT NULL COMMENT '客户端信息',
    `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (`id`),
    KEY `idx_audit_operator_time` (`operator_id`, `created_at`),
    KEY `idx_audit_action_time` (`action`, `created_at`),
    KEY `idx_audit_target` (`target_type`, `target_id`, `created_at`),
    KEY `idx_audit_result_time` (`result_status`, `created_at`),
    KEY `idx_audit_request_id` (`request_id`)
) ENGINE=InnoDB
  DEFAULT CHARSET=utf8mb4
  COMMENT='管理员高风险操作审计日志';
