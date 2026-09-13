-- =============================================================================
-- dev-sync-counters.sql — 联调种子后计数回写（可重复执行）
-- 回写: tb_content (liked, collect_count, comment_count)
--       tb_question_answer (like_count, comment_count)
--       tb_content_comment (like_count)
--
-- 用法:
--   mysql --host=127.0.0.1 --port=3306 --user=root -p --default-character-set=utf8mb4 demo < src/main/resources/db/dev-sync-counters.sql
--
-- 说明:
--   - 在 dev-seed-incremental.sql / dev-seed-gap-tables.sql 插入互动数据后执行
--   - 计数与运行时增量逻辑一致：按关系表 COUNT，评论/回答仅计 is_deleted=0
-- =============================================================================

SET NAMES utf8mb4;

-- -----------------------------------------------------------------------------
-- 1. tb_content — liked / collect_count / comment_count
-- -----------------------------------------------------------------------------
UPDATE tb_content c
SET
    liked = (
        SELECT COUNT(*) FROM tb_content_like cl WHERE cl.content_id = c.content_id
    ),
    collect_count = (
        SELECT COUNT(*) FROM tb_content_collect cc WHERE cc.content_id = c.content_id
    ),
    comment_count = (
        SELECT COUNT(*) FROM tb_content_comment cm
        WHERE cm.content_id = c.content_id AND cm.is_deleted = 0
    ),
    update_time = NOW()
WHERE c.is_deleted = 0
  AND (
      c.content_id IN (3, 4, 6, 10, 11, 12, 13)
      OR c.title LIKE '[seed-dev]%'
      OR EXISTS (SELECT 1 FROM tb_content_like cl WHERE cl.content_id = c.content_id)
      OR EXISTS (SELECT 1 FROM tb_content_collect cc WHERE cc.content_id = c.content_id)
      OR EXISTS (
          SELECT 1 FROM tb_content_comment cm
          WHERE cm.content_id = c.content_id AND cm.is_deleted = 0
      )
  );

-- -----------------------------------------------------------------------------
-- 2. tb_content_comment — like_count
-- -----------------------------------------------------------------------------
UPDATE tb_content_comment c
SET
    like_count = (
        SELECT COUNT(*) FROM tb_comment_like cl WHERE cl.comment_id = c.comment_id
    ),
    update_time = NOW()
WHERE c.is_deleted = 0
  AND (
      c.content LIKE '[seed-dev-comment%'
      OR c.comment_id = 3
      OR EXISTS (SELECT 1 FROM tb_comment_like cl WHERE cl.comment_id = c.comment_id)
  );

-- -----------------------------------------------------------------------------
-- 3. tb_question_answer — like_count / comment_count
-- -----------------------------------------------------------------------------
UPDATE tb_question_answer a
SET
    like_count = (
        SELECT COUNT(*) FROM tb_answer_like al WHERE al.answer_id = a.answer_id
    ),
    comment_count = (
        SELECT COUNT(*) FROM tb_content_comment cm
        WHERE cm.answer_id = a.answer_id AND cm.is_deleted = 0
    ),
    update_time = NOW()
WHERE a.is_deleted = 0
  AND (
      a.question_id = 4
      OR a.content LIKE '[seed-dev%'
      OR EXISTS (SELECT 1 FROM tb_answer_like al WHERE al.answer_id = a.answer_id)
      OR EXISTS (
          SELECT 1 FROM tb_content_comment cm
          WHERE cm.answer_id = a.answer_id AND cm.is_deleted = 0
      )
  );
