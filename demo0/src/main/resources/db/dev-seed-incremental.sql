-- =============================================================================
-- dev-seed-incremental.sql — 联调增量种子（可重复执行）
-- 数据库: demo @ 127.0.0.1:3306（与 application.yml 一致）
-- 用法: mysql -h127.0.0.1 -P3306 -uroot -p demo < src/main/resources/db/dev-seed-incremental.sql
--
-- 约定:
--   - 文本含 [seed-dev] 前缀的行用于 NOT EXISTS 防重复
--   - tb_user.auth_status 与 User 实体 / 小程序文案一致（0 未认证 1 审核中 2 已认证 3 不通过）
--   - tb_user_auth.audit_status 独立（0 待审 1 通过 2 驳回）
--   - 专业问答回答一律挂 question_id = 4（content_type=2 且已通过）
-- =============================================================================

SET NAMES utf8mb4;

-- -----------------------------------------------------------------------------
-- 0. tb_user — auth_status 对齐（仅 UPDATE，不新增用户）
-- -----------------------------------------------------------------------------
UPDATE tb_user SET auth_status = 2, update_time = NOW()
WHERE id IN (1, 3, 4, 6, 7) AND (auth_status IS NULL OR auth_status <> 2);

UPDATE tb_user SET auth_status = 1, update_time = NOW()
WHERE id = 9 AND (auth_status IS NULL OR auth_status <> 1);

UPDATE tb_user SET auth_status = 3, update_time = NOW()
WHERE id = 10 AND (auth_status IS NULL OR auth_status <> 3);

-- user 5 保持未认证 0；user 8 边界值 -1 不改动

-- -----------------------------------------------------------------------------
-- 1. tb_user_auth — 待审 / 驳回（user 5 不插入）
-- -----------------------------------------------------------------------------
INSERT INTO tb_user_auth (
    user_id, identity_type, real_name, school_id, quanta_batch, quanta_department,
    audit_status, audit_remark, audit_time, create_time, update_time
)
SELECT
    9, 1, '联调待审', '20250901', '2025', '产品部',
    0, NULL, NULL, NOW(), NOW()
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM tb_user_auth WHERE user_id = 9);

INSERT INTO tb_user_auth (
    user_id, identity_type, real_name, school_id, quanta_batch, quanta_department,
    audit_status, audit_remark, audit_time, create_time, update_time
)
SELECT
    10, 2, '联调驳回', '20180999', '2018', '运营部',
    2, '学号与证明材料不一致，请核对后重新提交', NOW(), DATE_SUB(NOW(), INTERVAL 3 DAY), NOW()
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM tb_user_auth WHERE user_id = 10);

-- 已通过用户若仅有旧 auth 行，确保 audit_status=1（user 3/4/6/7 若后续提交认证可再补行）
UPDATE tb_user_auth SET audit_status = 1, update_time = NOW()
WHERE user_id IN (3, 4, 6, 7) AND audit_status <> 1;

-- -----------------------------------------------------------------------------
-- 2. tb_content — 可选新生活帖 / 驳回帖
-- -----------------------------------------------------------------------------
INSERT INTO tb_content (
    content_type, title, content, publish_user_id, audit_status,
    liked, comment_count, collect_count, create_time, update_time, is_deleted
)
SELECT
    1, '[seed-dev] 联调生活帖-已通过', '周末部门团建求推荐场地，最好离学校近一些。', 3, 1,
    0, 0, 0, NOW(), NOW(), 0
FROM DUAL
WHERE NOT EXISTS (
    SELECT 1 FROM tb_content WHERE title = '[seed-dev] 联调生活帖-已通过' AND is_deleted = 0
);

INSERT INTO tb_content (
    content_type, title, content, publish_user_id, audit_status,
    liked, comment_count, collect_count, create_time, update_time, is_deleted
)
SELECT
    1, '[seed-dev] 联调生活帖-已驳回', '含违规推广信息的生活帖（种子数据）。', 5, 2,
    0, 0, 0, NOW(), NOW(), 0
FROM DUAL
WHERE NOT EXISTS (
    SELECT 1 FROM tb_content WHERE title = '[seed-dev] 联调生活帖-已驳回' AND is_deleted = 0
);

-- -----------------------------------------------------------------------------
-- 3. tb_question_answer — question_id = 4
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

-- 确保仅一条已采纳（采纳回答为 user 4 的已通过种子回答）
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
-- 4. tb_answer_image — 挂在已采纳回答
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
-- 5. tb_content_comment
-- -----------------------------------------------------------------------------
INSERT INTO tb_content_comment (
    content_id, answer_id, parent_id, reply_comment_id, reply_user_id,
    user_id, content, like_count, audit_status, is_deleted, create_time, update_time
)
SELECT
    10, NULL, NULL, NULL, NULL,
    4, '[seed-dev-comment-l1] 面试经验写得很详细，感谢分享！', 0, 1, 0, NOW(), NOW()
FROM DUAL
WHERE NOT EXISTS (
    SELECT 1 FROM tb_content_comment
    WHERE content_id = 10 AND is_deleted = 0 AND content LIKE '[seed-dev-comment-l1]%'
);

INSERT INTO tb_content_comment (
    content_id, answer_id, parent_id, reply_comment_id, reply_user_id,
    user_id, content, like_count, audit_status, is_deleted, create_time, update_time
)
SELECT
    10, NULL, p.comment_id, p.comment_id, p.user_id,
    3, '[seed-dev-comment-reply] 同感，准备按这个节奏复习。', 0, 1, 0, NOW(), NOW()
FROM tb_content_comment p
WHERE p.content_id = 10 AND p.is_deleted = 0 AND p.content LIKE '[seed-dev-comment-l1]%'
  AND NOT EXISTS (
      SELECT 1 FROM tb_content_comment
      WHERE content_id = 10 AND is_deleted = 0 AND content LIKE '[seed-dev-comment-reply]%'
  )
LIMIT 1;

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

INSERT INTO tb_content_comment (
    content_id, answer_id, parent_id, reply_comment_id, reply_user_id,
    user_id, content, like_count, audit_status, is_deleted, create_time, update_time
)
SELECT
    11, NULL, NULL, NULL, NULL,
    5, '[seed-dev-comment-pending] 待审核评论（仅管理端可见）。', 0, 0, 0, NOW(), NOW()
FROM DUAL
WHERE NOT EXISTS (
    SELECT 1 FROM tb_content_comment
    WHERE content_id = 11 AND is_deleted = 0 AND content LIKE '[seed-dev-comment-pending]%'
);

-- -----------------------------------------------------------------------------
-- 6. tb_comment_image — 一级评论配图
-- -----------------------------------------------------------------------------
INSERT INTO tb_comment_image (comment_id, image_url, sort)
SELECT
    c.comment_id,
    'https://java-ai13580089828.oss-cn-beijing.aliyuncs.com/dev-seed/posts/content-10.jpg',
    0
FROM tb_content_comment c
WHERE c.content_id = 10 AND c.is_deleted = 0 AND c.content LIKE '[seed-dev-comment-l1]%'
  AND NOT EXISTS (
      SELECT 1 FROM tb_comment_image ci WHERE ci.comment_id = c.comment_id
  )
LIMIT 1;

-- -----------------------------------------------------------------------------
-- 7. 互动表 — INSERT ... NOT EXISTS
-- -----------------------------------------------------------------------------
INSERT INTO tb_content_like (content_id, user_id, create_time)
SELECT 10, 4, NOW()
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM tb_content_like WHERE content_id = 10 AND user_id = 4);

INSERT INTO tb_content_like (content_id, user_id, create_time)
SELECT 11, 6, NOW()
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM tb_content_like WHERE content_id = 11 AND user_id = 6);

INSERT INTO tb_content_collect (content_id, user_id, create_time)
SELECT 10, 4, NOW()
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM tb_content_collect WHERE content_id = 10 AND user_id = 4);

INSERT INTO tb_content_collect (content_id, user_id, create_time)
SELECT 11, 4, NOW()
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM tb_content_collect WHERE content_id = 11 AND user_id = 4);

INSERT INTO tb_comment_like (comment_id, user_id)
SELECT c.comment_id, 3
FROM tb_content_comment c
WHERE c.content_id = 10 AND c.is_deleted = 0 AND c.content LIKE '[seed-dev-comment-l1]%'
  AND NOT EXISTS (
      SELECT 1 FROM tb_comment_like cl
      WHERE cl.comment_id = c.comment_id AND cl.user_id = 3
  )
LIMIT 1;

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

INSERT INTO tb_user_follow (user_id, follow_user_id, is_deleted, create_time, update_time)
SELECT 3, 4, 0, NOW(), NOW()
FROM DUAL
WHERE NOT EXISTS (
    SELECT 1 FROM tb_user_follow WHERE user_id = 3 AND follow_user_id = 4 AND is_deleted = 0
);

INSERT INTO tb_user_follow (user_id, follow_user_id, is_deleted, create_time, update_time)
SELECT 4, 6, 0, NOW(), NOW()
FROM DUAL
WHERE NOT EXISTS (
    SELECT 1 FROM tb_user_follow WHERE user_id = 4 AND follow_user_id = 6 AND is_deleted = 0
);

INSERT INTO tb_user_follow (user_id, follow_user_id, is_deleted, create_time, update_time)
SELECT 6, 3, 0, NOW(), NOW()
FROM DUAL
WHERE NOT EXISTS (
    SELECT 1 FROM tb_user_follow WHERE user_id = 6 AND follow_user_id = 3 AND is_deleted = 0
);

-- -----------------------------------------------------------------------------
-- 8. tb_browse_history
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
-- 9. tb_user_search_history
-- -----------------------------------------------------------------------------
INSERT INTO tb_user_search_history (user_id, keyword, create_time, is_deleted)
SELECT 1, 'Quanta', NOW(), 0
FROM DUAL
WHERE NOT EXISTS (
    SELECT 1 FROM tb_user_search_history WHERE user_id = 1 AND keyword = 'Quanta' AND is_deleted = 0
);

INSERT INTO tb_user_search_history (user_id, keyword, create_time, is_deleted)
SELECT 1, '面试', NOW(), 0
FROM DUAL
WHERE NOT EXISTS (
    SELECT 1 FROM tb_user_search_history WHERE user_id = 1 AND keyword = '面试' AND is_deleted = 0
);

INSERT INTO tb_user_search_history (user_id, keyword, create_time, is_deleted)
SELECT 1, '广外', NOW(), 0
FROM DUAL
WHERE NOT EXISTS (
    SELECT 1 FROM tb_user_search_history WHERE user_id = 1 AND keyword = '广外' AND is_deleted = 0
);

-- -----------------------------------------------------------------------------
-- 10. tb_notification — payload 用子查询取真实 id
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
-- 11. 举报表
-- -----------------------------------------------------------------------------
INSERT INTO tb_content_report (
    content_id, reporter_id, report_type, status, is_deleted, create_time, update_time
)
SELECT 3, 5, 1, 0, 0, NOW(), NOW()
FROM DUAL
WHERE NOT EXISTS (
    SELECT 1 FROM tb_content_report
    WHERE content_id = 3 AND reporter_id = 5 AND is_deleted = 0
);

INSERT INTO tb_content_report (
    content_id, reporter_id, report_type, status, handler_id, handle_result,
    handle_remark, handle_time, is_deleted, create_time, update_time
)
SELECT
    10, 6, 1, 2, 1, 1,
    '经核实无违规，维持原内容', NOW(), 0, DATE_SUB(NOW(), INTERVAL 2 DAY), NOW()
FROM DUAL
WHERE NOT EXISTS (
    SELECT 1 FROM tb_content_report
    WHERE content_id = 10 AND reporter_id = 6 AND is_deleted = 0
);

-- 待处理评论举报：comment_id=3 若已存在则跳过
INSERT INTO tb_comment_report (
    comment_id, reporter_id, report_type, status, is_deleted, create_time, update_time
)
SELECT 3, 5, 1, 0, 0, NOW(), NOW()
FROM DUAL
WHERE EXISTS (SELECT 1 FROM tb_content_comment WHERE comment_id = 3 AND is_deleted = 0)
  AND NOT EXISTS (
      SELECT 1 FROM tb_comment_report
      WHERE comment_id = 3 AND reporter_id = 5 AND is_deleted = 0
  );

INSERT INTO tb_comment_report (
    comment_id, reporter_id, report_type, status, handler_id, handle_result,
    handle_remark, handle_time, is_deleted, create_time, update_time
)
SELECT
    c.comment_id, 6, 1, 3, 1, 2,
    '举报理由不充分，予以驳回', NOW(), 0, DATE_SUB(NOW(), INTERVAL 1 DAY), NOW()
FROM tb_content_comment c
WHERE c.content_id = 11 AND c.is_deleted = 0 AND c.content LIKE '[seed-dev-comment-pending]%'
  AND NOT EXISTS (
      SELECT 1 FROM tb_comment_report cr
      WHERE cr.comment_id = c.comment_id AND cr.reporter_id = 6 AND cr.is_deleted = 0
  )
LIMIT 1;

-- -----------------------------------------------------------------------------
-- 12. 计数器回写（与 dev-sync-counters.sql 保持一致，可单独重跑后者）
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
