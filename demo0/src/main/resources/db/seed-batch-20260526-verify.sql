-- verify for seed-batch-20260526
SET NAMES utf8mb4;
SELECT COUNT(*) AS content_pending FROM tb_content WHERE content LIKE '[seed-batch-20260526]%' AND is_deleted = 0 AND audit_status = 0;
SELECT COUNT(*) AS answers_pending FROM tb_question_answer WHERE content LIKE '[seed-batch-20260526]%' AND is_deleted = 0 AND audit_status = 0;
SELECT COUNT(*) AS comments_pending FROM tb_content_comment WHERE content LIKE '[seed-batch-20260526]%' AND is_deleted = 0 AND audit_status = 0;
SELECT COUNT(*) AS images_total FROM tb_content_image WHERE image_url LIKE '%seed-batch-20260526%';
SELECT audit_status, COUNT(*) cnt FROM tb_content WHERE content LIKE '[seed-batch-20260526]%' AND is_deleted = 0 GROUP BY audit_status ORDER BY audit_status;
SELECT audit_status, COUNT(*) cnt FROM tb_question_answer WHERE content LIKE '[seed-batch-20260526]%' AND is_deleted = 0 GROUP BY audit_status ORDER BY audit_status;
SELECT audit_status, COUNT(*) cnt FROM tb_content_comment WHERE content LIKE '[seed-batch-20260526]%' AND is_deleted = 0 GROUP BY audit_status ORDER BY audit_status;