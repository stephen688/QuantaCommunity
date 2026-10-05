-- Outbox 日志关联编号：只增加可空元数据列，不回填历史请求编号。
-- 执行前请先确认目标表没有同名列；本脚本是显式一次性迁移，不由应用启动自动执行。
SELECT COUNT(*) AS trace_id_column_count
FROM information_schema.columns
WHERE table_schema = DATABASE()
  AND table_name = 'tb_outbox_event'
  AND column_name = 'trace_id';

ALTER TABLE tb_outbox_event
    ADD COLUMN trace_id VARCHAR(64) NULL COMMENT '日志关联编号，非幂等键';
