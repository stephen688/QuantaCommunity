-- =============================================================================
-- V_security_governance_verify.sql
--
-- 阶段3角色表迁移结果验证，只查询数据，不修改数据库。
-- =============================================================================

-- 1. 查看角色表结构和索引。
SHOW CREATE TABLE `user_role`;
SHOW INDEX FROM `user_role`;

-- 2. 查看各角色目前拥有的用户数。
SELECT
    `role_code`,
    COUNT(*) AS `user_count`
FROM `user_role`
GROUP BY `role_code`
ORDER BY `role_code`;

-- 3. 检查重复角色，正常结果应为0行。
SELECT
    `user_id`,
    `role_code`,
    COUNT(*) AS `duplicate_count`
FROM `user_role`
GROUP BY `user_id`, `role_code`
HAVING COUNT(*) > 1;

-- 4. 检查原有管理员是否全部获得SUPER_ADMIN，正常结果应为0行。
SELECT
    user_table.`id`,
    user_table.`nick_name`,
    user_table.`is_admin`
FROM `tb_user` user_table
LEFT JOIN `user_role` user_role_table
    ON user_role_table.`user_id` = user_table.`id`
    AND user_role_table.`role_code` = 'SUPER_ADMIN'
WHERE COALESCE(user_table.`is_admin`, 0) = 1
  AND COALESCE(user_table.`is_deleted`, 0) = 0
  AND user_role_table.`id` IS NULL;

-- 5. 查看现有管理员迁移结果。
SELECT
    user_table.`id`,
    user_table.`nick_name`,
    user_table.`is_admin`,
    user_role_table.`role_code`,
    user_role_table.`created_at`
FROM `tb_user` user_table
INNER JOIN `user_role` user_role_table
    ON user_role_table.`user_id` = user_table.`id`
WHERE user_role_table.`role_code` = 'SUPER_ADMIN'
ORDER BY user_table.`id`;

-- =============================================================================
-- 阶段4：管理员审计日志表结构验证
-- =============================================================================

-- 6. 查看审计日志表结构和索引。
SHOW CREATE TABLE admin_audit_log;

SELECT index_name, column_name, seq_in_index
FROM information_schema.statistics
WHERE table_schema = DATABASE()
  AND table_name = 'admin_audit_log'
ORDER BY index_name, seq_in_index;
