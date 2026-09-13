-- rollback for seed-batch-20260526
SET NAMES utf8mb4;
START TRANSACTION;

-- 1) 先删互动
DELETE cl FROM tb_content_like cl JOIN tb_content c ON c.content_id = cl.content_id WHERE c.content LIKE '[seed-batch-20260526]%';
DELETE cc FROM tb_content_collect cc JOIN tb_content c ON c.content_id = cc.content_id WHERE c.content LIKE '[seed-batch-20260526]%';
DELETE al FROM tb_answer_like al JOIN tb_question_answer a ON a.answer_id = al.answer_id WHERE a.content LIKE '[seed-batch-20260526]%';
DELETE cl FROM tb_comment_like cl JOIN tb_content_comment cm ON cm.comment_id = cl.comment_id WHERE cm.content LIKE '[seed-batch-20260526]%';

-- 2) 删图片
DELETE ci FROM tb_comment_image ci JOIN tb_content_comment cm ON cm.comment_id = ci.comment_id WHERE cm.content LIKE '[seed-batch-20260526]%';
DELETE ai FROM tb_answer_image ai JOIN tb_question_answer a ON a.answer_id = ai.answer_id WHERE a.content LIKE '[seed-batch-20260526]%';
DELETE i FROM tb_content_image i JOIN tb_content c ON c.content_id = i.content_id WHERE c.content LIKE '[seed-batch-20260526]%' OR i.image_url LIKE '%seed-batch-20260526%';

-- 3) 软删评论/回答/帖子
UPDATE tb_content_comment SET is_deleted = 1, update_time = NOW() WHERE content LIKE '[seed-batch-20260526]%' AND is_deleted = 0;
UPDATE tb_question_answer SET is_deleted = 1, update_time = NOW(), is_accepted = 0 WHERE content LIKE '[seed-batch-20260526]%' AND is_deleted = 0;
UPDATE tb_content SET is_deleted = 1, update_time = NOW() WHERE content LIKE '[seed-batch-20260526]%' AND is_deleted = 0;

-- 4) 对账
SOURCE src/main/resources/db/dev-sync-counters.sql;
COMMIT;