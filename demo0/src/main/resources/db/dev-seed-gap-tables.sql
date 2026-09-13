-- =============================================================================
-- dev-seed-gap-tables.sql — 联调缺口表增量（可重复执行）
-- 覆盖: tb_question_answer, tb_answer_image, tb_answer_like,
--       tb_browse_history, tb_notification
-- 计数回写见 dev-sync-counters.sql（本文件末尾亦内联执行）
--
-- 用法:
--   mysql --host=127.0.0.1 --port=3306 --user=root -p --default-character-set=utf8mb4 demo < src/main/resources/db/dev-seed-gap-tables.sql
--
-- 依赖:
--   - content_id=4 专业帖已存在且 audit_status=1
--   - content_id 10/11 生活帖已存在（通知 payload 用评论子查询）
--   - 通知中 IDENTITY_AUDIT_RESULT 依赖 tb_user_auth.user_id=10
-- 完整联调种子见 dev-seed-incremental.sql
-- =============================================================================

SET NAMES utf8mb4;

-- -----------------------------------------------------------------------------
-- 1. tb_question_answer — question_id = 4
-- -----------------------------------------------------------------------------
INSERT INTO tb_question_answer (
    question_id, user_id, content, like_count, comment_count,
    is_accepted, audit_status, is_deleted, create_time, update_time
)
SELECT
    4, 4,
    '[seed-dev] 广外白云校区学习氛围很好，图书馆和自习室资源充足，社团活动也很丰富，适合全面发展。',
    0, 0, 1, 1, 0, DATE_SUB(NOW(), INTERVAL 2 DAY), NOW()
FROM DUAL
WHERE NOT EXISTS (
    SELECT 1 FROM tb_question_answer
    WHERE question_id = 4 AND is_deleted = 0 AND content LIKE '[seed-dev] 广外白云%'
);

INSERT INTO tb_question_answer (
    question_id, user_id, content, like_count, comment_count,
    is_accepted, audit_status, is_deleted, create_time, update_time
)
SELECT
    4, 6,
    '[seed-dev] 作为校友，广外的国际化课程和实习机会都不错，建议多参加 Quanta 的分享会了解行业动态。',
    0, 0, 0, 1, 0, DATE_SUB(NOW(), INTERVAL 1 DAY), NOW()
FROM DUAL
WHERE NOT EXISTS (
    SELECT 1 FROM tb_question_answer
    WHERE question_id = 4 AND user_id = 6 AND is_deleted = 0 AND content LIKE '[seed-dev] 作为校友%'
);

INSERT INTO tb_question_answer (
    question_id, user_id, content, like_count, comment_count,
    is_accepted, audit_status, is_deleted, create_time, update_time
)
SELECT
    4, 4,
    '[seed-dev-pending] 补充回答：待管理员审核通过后才会在 C 端展示。',
    0, 0, 0, 0, 0, NOW(), NOW()
FROM DUAL
WHERE NOT EXISTS (
    SELECT 1 FROM tb_question_answer
    WHERE question_id = 4 AND is_deleted = 0 AND content LIKE '[seed-dev-pending]%'
);

UPDATE tb_question_answer
SET is_accepted = 0, update_time = NOW()
WHERE question_id = 4
  AND is_deleted = 0
  AND is_accepted = 1
  AND content NOT LIKE '[seed-dev] 广外白云%';

UPDATE tb_question_answer
SET is_accepted = 1, update_time = NOW()
WHERE question_id = 4
  AND is_deleted = 0
  AND audit_status = 1
  AND content LIKE '[seed-dev] 广外白云%';

-- -----------------------------------------------------------------------------
-- 2. tb_answer_image — 挂在已采纳回答
-- -----------------------------------------------------------------------------
INSERT INTO tb_answer_image (answer_id, image_url, sort, create_time)
SELECT
    a.answer_id,
    'https://java-ai13580089828.oss-cn-beijing.aliyuncs.com/dev-seed/posts/content-4.jpg',
    0,
    NOW()
FROM tb_question_answer a
WHERE a.question_id = 4
  AND a.is_deleted = 0
  AND a.is_accepted = 1
  AND a.content LIKE '[seed-dev] 广外白云%'
  AND NOT EXISTS (
      SELECT 1 FROM tb_answer_image ai WHERE ai.answer_id = a.answer_id
  )
LIMIT 1;

-- -----------------------------------------------------------------------------
-- 3. tb_answer_like
-- -----------------------------------------------------------------------------
INSERT INTO tb_answer_like (answer_id, user_id, create_time)
SELECT a.answer_id, 3, NOW()
FROM tb_question_answer a
WHERE a.question_id = 4
  AND a.is_deleted = 0
  AND a.is_accepted = 1
  AND a.content LIKE '[seed-dev] 广外白云%'
  AND NOT EXISTS (
      SELECT 1 FROM tb_answer_like al WHERE al.answer_id = a.answer_id AND al.user_id = 3
  )
LIMIT 1;

-- -----------------------------------------------------------------------------
-- 4. 专业帖回答下评论（通知/计数依赖，可选）
-- -----------------------------------------------------------------------------
INSERT INTO tb_content_comment (
    content_id, answer_id, parent_id, reply_comment_id, reply_user_id,
    user_id, content, like_count, audit_status, is_deleted, create_time, update_time
)
SELECT
    4, a.answer_id, NULL, NULL, NULL,
    4, '[seed-dev-comment-on-answer] 赞同，校园环境确实是广外的亮点之一。', 0, 1, 0, NOW(), NOW()
FROM tb_question_answer a
WHERE a.question_id = 4
  AND a.is_deleted = 0
  AND a.is_accepted = 1
  AND a.content LIKE '[seed-dev] 广外白云%'
  AND NOT EXISTS (
      SELECT 1 FROM tb_content_comment
      WHERE content_id = 4 AND is_deleted = 0 AND content LIKE '[seed-dev-comment-on-answer]%'
  )
LIMIT 1;

-- -----------------------------------------------------------------------------
-- 5. tb_browse_history
-- -----------------------------------------------------------------------------
INSERT INTO tb_browse_history (user_id, content_id, browse_date, create_time, update_time, is_deleted)
SELECT 1, 4, CURDATE(), NOW(), NOW(), 0
FROM DUAL
WHERE NOT EXISTS (
    SELECT 1 FROM tb_browse_history
    WHERE user_id = 1 AND content_id = 4 AND browse_date = CURDATE() AND is_deleted = 0
);

INSERT INTO tb_browse_history (user_id, content_id, browse_date, create_time, update_time, is_deleted)
SELECT 1, 10, CURDATE(), NOW(), NOW(), 0
FROM DUAL
WHERE NOT EXISTS (
    SELECT 1 FROM tb_browse_history
    WHERE user_id = 1 AND content_id = 10 AND browse_date = CURDATE() AND is_deleted = 0
);

INSERT INTO tb_browse_history (user_id, content_id, browse_date, create_time, update_time, is_deleted)
SELECT 1, 11, DATE_SUB(CURDATE(), INTERVAL 1 DAY), DATE_SUB(NOW(), INTERVAL 1 DAY), NOW(), 0
FROM DUAL
WHERE NOT EXISTS (
    SELECT 1 FROM tb_browse_history
    WHERE user_id = 1 AND content_id = 11 AND browse_date = DATE_SUB(CURDATE(), INTERVAL 1 DAY) AND is_deleted = 0
);

-- -----------------------------------------------------------------------------
-- 6. tb_notification
-- -----------------------------------------------------------------------------
INSERT INTO tb_notification (
    recipient_user_id, actor_user_id, type, content, payload,
    is_read, create_time, update_time, is_deleted
)
SELECT
    4, 1, 'ANSWER_ACCEPTED', '你的回答被采纳',
    JSON_OBJECT(
        'contentId', 4,
        'answerId', (
            SELECT a.answer_id FROM tb_question_answer a
            WHERE a.question_id = 4 AND a.is_deleted = 0 AND a.is_accepted = 1
            ORDER BY a.answer_id DESC LIMIT 1
        )
    ),
    0, NOW(), NOW(), 0
FROM DUAL
WHERE NOT EXISTS (
    SELECT 1 FROM tb_notification
    WHERE recipient_user_id = 4 AND type = 'ANSWER_ACCEPTED' AND is_deleted = 0
      AND content = '你的回答被采纳'
);

INSERT INTO tb_notification (
    recipient_user_id, actor_user_id, type, content, payload,
    is_read, create_time, update_time, is_deleted
)
SELECT
    1, 4, 'COMMENT_ON_CONTENT', '评论了你的内容',
    JSON_OBJECT(
        'contentId', 10,
        'commentId', (
            SELECT c.comment_id FROM tb_content_comment c
            WHERE c.content_id = 10 AND c.is_deleted = 0 AND c.content LIKE '[seed-dev-comment-l1]%'
            LIMIT 1
        )
    ),
    0, NOW(), NOW(), 0
FROM DUAL
WHERE NOT EXISTS (
    SELECT 1 FROM tb_notification
    WHERE recipient_user_id = 1 AND type = 'COMMENT_ON_CONTENT' AND is_deleted = 0
      AND content = '评论了你的内容'
      AND payload LIKE '%"contentId":10%'
);

INSERT INTO tb_notification (
    recipient_user_id, actor_user_id, type, content, payload,
    is_read, create_time, update_time, is_deleted
)
SELECT
    4, 3, 'COMMENT_REPLY', '回复了你的评论',
    JSON_OBJECT(
        'contentId', 10,
        'commentId', (
            SELECT c.comment_id FROM tb_content_comment c
            WHERE c.content_id = 10 AND c.is_deleted = 0 AND c.content LIKE '[seed-dev-comment-reply]%'
            LIMIT 1
        ),
        'replyCommentId', (
            SELECT c.comment_id FROM tb_content_comment c
            WHERE c.content_id = 10 AND c.is_deleted = 0 AND c.content LIKE '[seed-dev-comment-l1]%'
            LIMIT 1
        )
    ),
    0, NOW(), NOW(), 0
FROM DUAL
WHERE NOT EXISTS (
    SELECT 1 FROM tb_notification
    WHERE recipient_user_id = 4 AND type = 'COMMENT_REPLY' AND is_deleted = 0
      AND content = '回复了你的评论'
);

INSERT INTO tb_notification (
    recipient_user_id, actor_user_id, type, content, payload,
    is_read, create_time, update_time, is_deleted
)
SELECT
    3, 4, 'LIKE_CONTENT', '点赞了你的内容',
    JSON_OBJECT('contentId', 10),
    1, DATE_SUB(NOW(), INTERVAL 1 DAY), NOW(), 0
FROM DUAL
WHERE NOT EXISTS (
    SELECT 1 FROM tb_notification
    WHERE recipient_user_id = 3 AND type = 'LIKE_CONTENT' AND is_deleted = 0
      AND payload LIKE '%"contentId":10%'
);

INSERT INTO tb_notification (
    recipient_user_id, actor_user_id, type, content, payload,
    is_read, create_time, update_time, is_deleted
)
SELECT
    4, 3, 'LIKE_ANSWER', '点赞了你的回答',
    JSON_OBJECT(
        'contentId', 4,
        'answerId', (
            SELECT a.answer_id FROM tb_question_answer a
            WHERE a.question_id = 4 AND a.is_deleted = 0 AND a.is_accepted = 1
            LIMIT 1
        )
    ),
    0, NOW(), NOW(), 0
FROM DUAL
WHERE NOT EXISTS (
    SELECT 1 FROM tb_notification
    WHERE recipient_user_id = 4 AND type = 'LIKE_ANSWER' AND is_deleted = 0
);

INSERT INTO tb_notification (
    recipient_user_id, actor_user_id, type, content, payload,
    is_read, create_time, update_time, is_deleted
)
SELECT
    4, 3, 'USER_FOLLOW', '关注了你', JSON_OBJECT(),
    0, NOW(), NOW(), 0
FROM DUAL
WHERE NOT EXISTS (
    SELECT 1 FROM tb_notification
    WHERE recipient_user_id = 4 AND type = 'USER_FOLLOW' AND actor_user_id = 3 AND is_deleted = 0
);

INSERT INTO tb_notification (
    recipient_user_id, actor_user_id, type, content, payload,
    is_read, create_time, update_time, is_deleted
)
SELECT
    4, 3, 'LIKE_COMMENT', '点赞了你的评论',
    JSON_OBJECT(
        'contentId', 10,
        'commentId', (
            SELECT c.comment_id FROM tb_content_comment c
            WHERE c.content_id = 10 AND c.is_deleted = 0 AND c.content LIKE '[seed-dev-comment-l1]%'
            LIMIT 1
        )
    ),
    0, NOW(), NOW(), 0
FROM DUAL
WHERE NOT EXISTS (
    SELECT 1 FROM tb_notification
    WHERE recipient_user_id = 4 AND type = 'LIKE_COMMENT' AND is_deleted = 0
);

INSERT INTO tb_notification (
    recipient_user_id, actor_user_id, type, content, payload,
    is_read, create_time, update_time, is_deleted
)
SELECT
    10, NULL, 'IDENTITY_AUDIT_RESULT', '你的身份认证未通过，原因：学号与证明材料不一致，请核对后重新提交',
    JSON_OBJECT(
        'authId', (SELECT ua.auth_id FROM tb_user_auth ua WHERE ua.user_id = 10 LIMIT 1),
        'auditResult', 2,
        'auditRemark', '学号与证明材料不一致，请核对后重新提交'
    ),
    0, NOW(), NOW(), 0
FROM DUAL
WHERE NOT EXISTS (
    SELECT 1 FROM tb_notification
    WHERE recipient_user_id = 10 AND type = 'IDENTITY_AUDIT_RESULT' AND is_deleted = 0
);

-- -----------------------------------------------------------------------------
-- 7. 计数器回写（与 dev-sync-counters.sql 一致）
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
