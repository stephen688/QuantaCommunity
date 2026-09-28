-- 推荐主题与显式偏好迁移：先执行本脚本，再部署应用；回滚代码时保留新列/事实表。
SET @topic_column_sql = IF(
    (SELECT COUNT(*) FROM information_schema.columns
     WHERE table_schema=DATABASE() AND table_name='tb_content' AND column_name='tags')=0,
    'ALTER TABLE tb_content ADD COLUMN tags JSON NULL COMMENT ''受控主题ID数组；NULL未处理，[]无命中''',
    'SELECT 1');
PREPARE topic_column_statement FROM @topic_column_sql;
EXECUTE topic_column_statement;
DEALLOCATE PREPARE topic_column_statement;

CREATE TABLE IF NOT EXISTS tb_user_profile_signal (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    event_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    user_id BIGINT NOT NULL,
    memory_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    persona_version VARCHAR(64) NOT NULL,
    revision BIGINT NOT NULL COMMENT '源记忆变更的UTC微秒版本',
    operation VARCHAR(8) NOT NULL,
    topics JSON NOT NULL COMMENT '仅主题ID；不存原文',
    valence VARCHAR(8) NULL,
    create_time DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    UNIQUE KEY uk_profile_signal_event (event_id),
    KEY idx_profile_signal_state (user_id,memory_id,persona_version,revision,id),
    KEY idx_profile_signal_user_generation (user_id,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_as_cs;
