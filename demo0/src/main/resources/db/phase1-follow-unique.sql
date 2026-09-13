-- 第一阶段：关注关系使用一对用户一条记录，is_deleted 只表示当前状态。
-- 执行前请确认同一 user_id + follow_user_id 没有重复数据。
SELECT user_id, follow_user_id, COUNT(*) AS duplicate_count
FROM tb_user_follow
GROUP BY user_id, follow_user_id
HAVING COUNT(*) > 1;

SET @old_index_exists = (
    SELECT COUNT(*)
    FROM information_schema.statistics
    WHERE table_schema = DATABASE()
      AND table_name = 'tb_user_follow'
      AND index_name = 'uk_user_follow'
);
SET @drop_old_index_sql = IF(
    @old_index_exists > 0,
    'ALTER TABLE tb_user_follow DROP INDEX uk_user_follow',
    'SELECT 1'
);
PREPARE drop_old_index_stmt FROM @drop_old_index_sql;
EXECUTE drop_old_index_stmt;
DEALLOCATE PREPARE drop_old_index_stmt;

SET @new_index_exists = (
    SELECT COUNT(*)
    FROM information_schema.statistics
    WHERE table_schema = DATABASE()
      AND table_name = 'tb_user_follow'
      AND index_name = 'uk_user_follow_pair'
);
SET @add_new_index_sql = IF(
    @new_index_exists = 0,
    'ALTER TABLE tb_user_follow ADD UNIQUE KEY uk_user_follow_pair (user_id, follow_user_id)',
    'SELECT 1'
);
PREPARE add_new_index_stmt FROM @add_new_index_sql;
EXECUTE add_new_index_stmt;
DEALLOCATE PREPARE add_new_index_stmt;
