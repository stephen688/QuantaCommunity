#!/usr/bin/env python3
"""Generate phase-2 seed SQL/manifest from confirmed plan."""
from __future__ import annotations

import json
import random
from dataclasses import dataclass
from pathlib import Path

BATCH = "seed-batch-20260526"
BATCH_TAG = f"[{BATCH}]"
OUT_DB_DIR = Path("src/main/resources/db")
OUT_MANIFEST = Path("scripts") / f"{BATCH}.manifest.json"

REUSE_URLS = [
    "https://java-ai13580089828.oss-cn-beijing.aliyuncs.com/f244929f-75d8-4ec3-ab3c-a8f8fa8e7da9.jpg",
    "https://java-ai13580089828.oss-cn-beijing.aliyuncs.com/5deaac9c-395b-4f0b-b832-2dff69a42b8b.jpg",
    "https://java-ai13580089828.oss-cn-beijing.aliyuncs.com/8ba45ba6-df32-473f-a722-8c13d131fbba.png",
    "https://java-ai13580089828.oss-cn-beijing.aliyuncs.com/43bba7ee-a2fb-459c-a0a3-57c210fac2b2.png",
    "https://java-ai13580089828.oss-cn-beijing.aliyuncs.com/1f1bba8b-0988-42c7-8beb-5cd90b908ece.png",
    "https://java-ai13580089828.oss-cn-beijing.aliyuncs.com/d07e224f-a8f6-4fed-8a4c-25e58d19045e.jpg",
    "https://java-ai13580089828.oss-cn-beijing.aliyuncs.com/dev-seed/posts/content-12.jpg?v=realistic2",
    "https://java-ai13580089828.oss-cn-beijing.aliyuncs.com/dev-seed/posts/content-11.jpg?v=realistic2",
]

PRO_ANSWER_POOL = [4, 6, 15, 16, 20, 21, 23, 25]
INTERACTOR_POOL = [1, 3, 4, 5, 6, 8, 9, 10, 11, 12, 13, 15, 16, 17, 18, 20, 21, 22, 23, 24, 25, 26, 27]


@dataclass
class LifePost:
    code: str
    title: str
    author: int
    created_at: str
    likes: int
    collects: int
    comments: int
    with_images: bool


@dataclass
class ProPost:
    code: str
    title: str
    author: int
    created_at: str
    likes: int
    collects: int
    answers: int
    comments_per_answer: tuple[int, int]
    with_image: bool


LIFE_POSTS = [
    LifePost("L01", "二饭晚高峰怎么避开排队", 18, "2026-04-28 19:40:00", 8, 2, 4, False),
    LifePost("L02", "北校快递点这周是不是提前关门", 22, "2026-04-29 18:55:00", 6, 1, 3, False),
    LifePost("L03", "宿舍空调滴水，报修要走哪个入口", 12, "2026-04-30 21:15:00", 11, 3, 6, True),
    LifePost("L04", "白云湖跑步路线，晚风舒服那条求推荐", 17, "2026-05-01 17:20:00", 7, 2, 3, False),
    LifePost("L05", "操场失物招领：黑色水杯有贴纸", 5, "2026-05-02 12:10:00", 5, 0, 2, False),
    LifePost("L06", "南门打印店彩印论文哪家不翻车", 11, "2026-05-03 14:30:00", 10, 4, 5, True),
    LifePost("L07", "雨天校巴等了40分钟，今天啥情况", 9, "2026-05-04 08:35:00", 9, 2, 4, False),
    LifePost("L08", "周末想拼羽毛球，三缺一", 3, "2026-05-05 15:10:00", 12, 1, 7, True),
    LifePost("L09", "求二手显示器，预算500内", 23, "2026-05-06 20:05:00", 8, 3, 5, False),
    LifePost("L10", "图书馆插座区又满了，有替代自习点吗", 1, "2026-05-07 19:00:00", 6, 1, 3, False),
    LifePost("L11", "学校附近夜宵面店推荐，别太油", 25, "2026-05-08 22:10:00", 13, 4, 8, True),
    LifePost("L12", "这周广外天气忽冷忽热，穿搭怎么选", 24, "2026-05-09 11:40:00", 7, 1, 4, True),
    LifePost("L13", "社团面试自我介绍会不会太模板化", 13, "2026-05-10 19:50:00", 9, 2, 5, False),
    LifePost("L14", "失物招领：教学楼A区门口耳机盒", 27, "2026-05-11 13:25:00", 4, 0, 2, False),
    LifePost("L15", "宿舍夜聊到两点，第二天早八怎么活", 26, "2026-05-12 23:05:00", 12, 3, 6, True),
    LifePost("L16", "白云校区周末哪里拍照出片", 8, "2026-05-20 14:45:00", 14, 5, 7, True),
    LifePost("L17", "二手书交换：计网王道和高数习题", 15, "2026-05-21 16:30:00", 8, 2, 4, False),
    LifePost("L18", "快递驿站排队太夸张，有没有避峰时段", 6, "2026-05-23 18:20:00", 10, 3, 5, True),
    LifePost("L19", "周末校内桌游局，狼人杀新手友好", 4, "2026-05-24 15:05:00", 11, 1, 6, True),
    LifePost("L20", "食堂新窗口的牛肉饭值不值", 10, "2026-05-25 12:35:00", 15, 4, 8, True),
]

PRO_POSTS = [
    ProPost("P01", "SpringBoot项目启动慢，定位卡点思路", 16, "2026-04-28 21:05:00", 7, 3, 2, (2, 3), False),
    ProPost("P02", "Java并发作业：线程池参数怎么给才合理", 20, "2026-04-29 20:20:00", 9, 4, 3, (2, 4), False),
    ProPost("P03", "MySQL索引实验：联合索引顺序总记混", 21, "2026-04-30 19:35:00", 8, 3, 2, (1, 3), False),
    ProPost("P04", "前端项目首屏白屏，排查顺序求建议", 15, "2026-05-01 22:00:00", 7, 2, 2, (1, 2), False),
    ProPost("P05", "软工毕设选题：校园服务类会不会太卷", 6, "2026-05-02 16:10:00", 10, 5, 4, (2, 4), True),
    ProPost("P06", "产品实习简历没亮点，项目经历怎么写", 9, "2026-05-03 20:40:00", 11, 5, 3, (2, 4), False),
    ProPost("P07", "四六级和专业课复习冲突，时间怎么拆", 11, "2026-05-04 18:15:00", 6, 3, 2, (1, 2), False),
    ProPost("P08", "算法课动态规划总是写不出状态转移", 23, "2026-05-05 21:30:00", 9, 4, 3, (2, 3), False),
    ProPost("P09", "计网实验抓包报告怎么写得像样", 4, "2026-05-06 20:00:00", 7, 2, 2, (1, 2), False),
    ProPost("P10", "Vue路由守卫和登录态刷新冲突怎么解", 3, "2026-05-07 19:25:00", 10, 4, 3, (1, 3), False),
    ProPost("P11", "考研数学和Java实习并行，作息怎么排", 13, "2026-05-08 22:15:00", 8, 4, 2, (2, 3), False),
    ProPost("P12", "选课避坑：数据库原理谁讲得更清楚", 18, "2026-05-09 10:55:00", 7, 3, 2, (1, 2), False),
    ProPost("P13", "接口压测QPS上不去，瓶颈可能在哪", 16, "2026-05-10 20:35:00", 9, 4, 3, (2, 4), False),
    ProPost("P14", "Redis缓存击穿作业，老师要写降级方案", 20, "2026-05-11 19:50:00", 8, 3, 2, (1, 3), False),
    ProPost("P15", "设计岗作品集：校园项目怎么讲故事", 8, "2026-05-12 17:40:00", 9, 4, 3, (1, 3), False),
    ProPost("P16", "英语口语面试紧张，技术表达总卡壳", 27, "2026-05-13 21:45:00", 6, 2, 2, (1, 2), False),
    ProPost("P17", "Nginx反代后WebSocket经常断连", 1, "2026-05-14 20:10:00", 8, 3, 2, (2, 3), False),
    ProPost("P18", "Java八股背了但项目问答还是慌", 25, "2026-05-20 19:15:00", 12, 5, 4, (2, 4), True),
    ProPost("P19", "小程序分包后首包变小但白屏更久", 24, "2026-05-21 20:50:00", 10, 4, 3, (1, 3), False),
    ProPost("P20", "毕设中期答辩PPT结构求模板", 26, "2026-05-22 16:25:00", 8, 3, 2, (1, 2), False),
    ProPost("P21", "Spring事务失效的常见坑，求排查清单", 4, "2026-05-23 21:05:00", 13, 5, 4, (2, 5), True),
    ProPost("P22", "SQL慢查询日志看不懂，先看哪几列", 21, "2026-05-24 20:40:00", 11, 4, 3, (2, 4), False),
    ProPost("P23", "算法作业最短路，Dijkstra和Floyd怎么选", 10, "2026-05-24 14:30:00", 10, 4, 3, (1, 3), False),
    ProPost("P24", "前端组件库封装到一半命名全乱了", 15, "2026-05-24 22:05:00", 9, 3, 2, (1, 2), False),
    ProPost("P25", "产品经理实习群面，案例题怎么破题", 6, "2026-05-25 10:15:00", 12, 5, 3, (2, 4), True),
    ProPost("P26", "毕业学长：中厂后端实习转正复盘", 16, "2026-05-25 21:10:00", 14, 5, 2, (2, 4), True),
    ProPost("P27", "课程设计要做推荐系统，冷启动怎么写", 20, "2026-05-26 11:20:00", 13, 5, 4, (2, 5), False),
    ProPost("P28", "校招笔试手撕链表总超时，训练路径求推", 23, "2026-05-26 12:40:00", 11, 4, 3, (2, 4), False),
    ProPost("P29", "考研复试英文自我介绍，技术项目怎么说", 11, "2026-05-26 13:05:00", 9, 3, 2, (1, 3), False),
    ProPost("P30", "广外计科选课避坑：数据库+编译原理组合", 3, "2026-05-26 13:25:00", 12, 4, 3, (2, 4), False),
]

LIFE_IMAGE_CODES = {"L03", "L06", "L08", "L11", "L12", "L15", "L16", "L18", "L19", "L20"}
PRO_IMAGE_CODES = {"P05", "P18", "P21", "P25", "P26"}


def esc(value: str) -> str:
    return value.replace("\\", "\\\\").replace("'", "''")


def sample_users(rng: random.Random, n: int, author: int) -> list[int]:
    pool = [u for u in INTERACTOR_POOL if u != author]
    rng.shuffle(pool)
    return pool[:n]


def write_seed_sql() -> tuple[str, dict]:
    rng = random.Random(20260526)
    lines: list[str] = []
    manifest: dict = {
        "batch": BATCH,
        "batch_tag": BATCH_TAG,
        "life_titles": [p.title for p in LIFE_POSTS],
        "pro_titles": [p.title for p in PRO_POSTS],
    }

    lines.extend(
        [
            f"-- auto-generated by scripts/generate_seed_phase2.py ({BATCH})",
            "SET NAMES utf8mb4;",
            "",
            "-- 1) 插入 50 条待审帖子",
        ]
    )
    for p in LIFE_POSTS:
        body = f"{BATCH_TAG} {p.code} 生活区种子帖：{p.title}。用于联调审核、曝光与推荐链路。"
        lines.extend(
            [
                "INSERT INTO tb_content (content_type, title, content, publish_user_id, audit_status, liked, comment_count, collect_count, create_time, update_time, is_deleted)",
                f"SELECT 1, '{esc(p.title)}', '{esc(body)}', {p.author}, 0, 0, 0, 0, '{p.created_at}', '{p.created_at}', 0",
                "FROM DUAL",
                f"WHERE NOT EXISTS (SELECT 1 FROM tb_content WHERE title = '{esc(p.title)}' AND is_deleted = 0);",
                "",
            ]
        )

    for p in PRO_POSTS:
        body = f"{BATCH_TAG} {p.code} 专业区种子帖：{p.title}。用于联调问题-回答-评论审核链路。"
        lines.extend(
            [
                "INSERT INTO tb_content (content_type, title, content, publish_user_id, audit_status, liked, comment_count, collect_count, create_time, update_time, is_deleted)",
                f"SELECT 2, '{esc(p.title)}', '{esc(body)}', {p.author}, 0, 0, 0, 0, '{p.created_at}', '{p.created_at}', 0",
                "FROM DUAL",
                f"WHERE NOT EXISTS (SELECT 1 FROM tb_content WHERE title = '{esc(p.title)}' AND is_deleted = 0);",
                "",
            ]
        )

    lines.extend(["-- 2) 配图（生活 10 帖、专业 5 帖）", ""])
    image_idx = 1
    for p in LIFE_POSTS:
        if p.code not in LIFE_IMAGE_CODES:
            continue
        count = 2 if p.code in {"L03", "L06", "L12"} else 3
        for s in range(count):
            url = (
                REUSE_URLS[(image_idx + s) % len(REUSE_URLS)]
                if s == 0
                else f"https://java-ai13580089828.oss-cn-beijing.aliyuncs.com/dev-seed/{BATCH}/{p.code}-{s}.jpg"
            )
            lines.extend(
                [
                    "INSERT INTO tb_content_image (content_id, image_url, sort)",
                    f"SELECT c.content_id, '{url}', {s}",
                    "FROM tb_content c",
                    f"WHERE c.title = '{esc(p.title)}' AND c.is_deleted = 0",
                    f"  AND NOT EXISTS (SELECT 1 FROM tb_content_image i WHERE i.content_id = c.content_id AND i.image_url = '{url}');",
                    "",
                ]
            )
        image_idx += 1
    for p in PRO_POSTS:
        if p.code not in PRO_IMAGE_CODES:
            continue
        url = REUSE_URLS[image_idx % len(REUSE_URLS)]
        lines.extend(
            [
                "INSERT INTO tb_content_image (content_id, image_url, sort)",
                f"SELECT c.content_id, '{url}', 0",
                "FROM tb_content c",
                f"WHERE c.title = '{esc(p.title)}' AND c.is_deleted = 0",
                f"  AND NOT EXISTS (SELECT 1 FROM tb_content_image i WHERE i.content_id = c.content_id AND i.image_url = '{url}');",
                "",
            ]
        )
        image_idx += 1

    lines.extend(["-- 3) 专业帖回答（全部待审，单帖 1 条标记采纳）", ""])
    answer_keys: list[str] = []
    for p in PRO_POSTS:
        ans_users = [u for u in PRO_ANSWER_POOL if u != p.author]
        rng.shuffle(ans_users)
        ans_users = ans_users[: p.answers]
        accepted = ans_users[0]
        for i, uid in enumerate(ans_users, start=1):
            answer_key = f"{p.code}-A{i}"
            answer_keys.append(answer_key)
            ans_text = f"{BATCH_TAG} {answer_key} 针对《{p.title}》的回答，建议先从可复现最小样例开始排查。"
            is_accepted = 1 if uid == accepted else 0
            lines.extend(
                [
                    "INSERT INTO tb_question_answer (question_id, user_id, content, like_count, comment_count, is_accepted, audit_status, is_deleted, create_time, update_time)",
                    f"SELECT c.content_id, {uid}, '{esc(ans_text)}', 0, 0, {is_accepted}, 0, 0, '{p.created_at}', '{p.created_at}'",
                    "FROM tb_content c",
                    f"WHERE c.title = '{esc(p.title)}' AND c.is_deleted = 0",
                    f"  AND NOT EXISTS (SELECT 1 FROM tb_question_answer a WHERE a.question_id = c.content_id AND a.content LIKE '{BATCH_TAG} {answer_key}%');",
                    "",
                ]
            )
    manifest["answer_keys"] = answer_keys

    lines.extend(["-- 4) 评论（生活评论不带 answer_id；专业评论全部挂 answer_id）", ""])
    for p in LIFE_POSTS:
        users = sample_users(rng, p.comments, p.author)
        for idx, uid in enumerate(users, start=1):
            txt = f"{BATCH_TAG} {p.code}-C{idx} 生活评论：补充一下我的实际体验。"
            lines.extend(
                [
                    "INSERT INTO tb_content_comment (content_id, answer_id, parent_id, reply_comment_id, reply_user_id, user_id, content, like_count, audit_status, is_deleted, create_time, update_time)",
                    f"SELECT c.content_id, NULL, NULL, NULL, NULL, {uid}, '{esc(txt)}', 0, 0, 0, '{p.created_at}', '{p.created_at}'",
                    "FROM tb_content c",
                    f"WHERE c.title = '{esc(p.title)}' AND c.is_deleted = 0",
                    f"  AND NOT EXISTS (SELECT 1 FROM tb_content_comment cm WHERE cm.content_id = c.content_id AND cm.content LIKE '{BATCH_TAG} {p.code}-C{idx}%');",
                    "",
                ]
            )

    for p in PRO_POSTS:
        for i in range(1, p.answers + 1):
            answer_key = f"{p.code}-A{i}"
            cnt = rng.randint(*p.comments_per_answer)
            users = sample_users(rng, cnt, p.author)
            for j, uid in enumerate(users, start=1):
                txt = f"{BATCH_TAG} {answer_key}-C{j} 追问：这个方案在课程作业里能落地吗？"
                lines.extend(
                    [
                        "INSERT INTO tb_content_comment (content_id, answer_id, parent_id, reply_comment_id, reply_user_id, user_id, content, like_count, audit_status, is_deleted, create_time, update_time)",
                        f"SELECT c.content_id, a.answer_id, NULL, NULL, NULL, {uid}, '{esc(txt)}', 0, 0, 0, '{p.created_at}', '{p.created_at}'",
                        "FROM tb_content c",
                        "JOIN tb_question_answer a ON a.question_id = c.content_id",
                        f"WHERE c.title = '{esc(p.title)}' AND c.is_deleted = 0 AND a.content LIKE '{BATCH_TAG} {answer_key}%'",
                        f"  AND NOT EXISTS (SELECT 1 FROM tb_content_comment cm WHERE cm.answer_id = a.answer_id AND cm.content LIKE '{BATCH_TAG} {answer_key}-C{j}%');",
                        "",
                    ]
                )

    lines.extend(["-- 5) 点赞 / 收藏（全部待审记录也可入明细，最终由审核曝光）", ""])
    all_posts = [(p.title, p.author, p.likes, p.collects) for p in LIFE_POSTS] + [
        (p.title, p.author, p.likes, p.collects) for p in PRO_POSTS
    ]
    for title, author, likes, collects in all_posts:
        for uid in sample_users(rng, likes, author):
            lines.extend(
                [
                    "INSERT INTO tb_content_like (content_id, user_id, create_time)",
                    f"SELECT c.content_id, {uid}, NOW() FROM tb_content c",
                    f"WHERE c.title = '{esc(title)}' AND c.is_deleted = 0",
                    f"  AND NOT EXISTS (SELECT 1 FROM tb_content_like l WHERE l.content_id = c.content_id AND l.user_id = {uid});",
                    "",
                ]
            )
        for uid in sample_users(rng, collects, author):
            lines.extend(
                [
                    "INSERT INTO tb_content_collect (content_id, user_id, create_time)",
                    f"SELECT c.content_id, {uid}, NOW() FROM tb_content c",
                    f"WHERE c.title = '{esc(title)}' AND c.is_deleted = 0",
                    f"  AND NOT EXISTS (SELECT 1 FROM tb_content_collect f WHERE f.content_id = c.content_id AND f.user_id = {uid});",
                    "",
                ]
            )

    lines.extend(
        [
            "-- 6) answer/comment 点赞（轻量补齐）",
            "INSERT INTO tb_answer_like (answer_id, user_id, create_time)",
            "SELECT a.answer_id, 3, NOW() FROM tb_question_answer a",
            f"WHERE a.content LIKE '{BATCH_TAG}%'",
            "  AND NOT EXISTS (SELECT 1 FROM tb_answer_like al WHERE al.answer_id = a.answer_id AND al.user_id = 3);",
            "",
            "INSERT INTO tb_comment_like (comment_id, user_id)",
            "SELECT cm.comment_id, 6 FROM tb_content_comment cm",
            f"WHERE cm.content LIKE '{BATCH_TAG}%'",
            "  AND NOT EXISTS (SELECT 1 FROM tb_comment_like cl WHERE cl.comment_id = cm.comment_id AND cl.user_id = 6);",
            "",
            "-- 7) 冗余计数对账",
            "SOURCE src/main/resources/db/dev-sync-counters.sql;",
            "",
        ]
    )
    return "\n".join(lines), manifest


def write_verify_sql() -> str:
    return "\n".join(
        [
            f"-- verify for {BATCH}",
            "SET NAMES utf8mb4;",
            f"SELECT COUNT(*) AS content_pending FROM tb_content WHERE content LIKE '{BATCH_TAG}%' AND is_deleted = 0 AND audit_status = 0;",
            f"SELECT COUNT(*) AS answers_pending FROM tb_question_answer WHERE content LIKE '{BATCH_TAG}%' AND is_deleted = 0 AND audit_status = 0;",
            f"SELECT COUNT(*) AS comments_pending FROM tb_content_comment WHERE content LIKE '{BATCH_TAG}%' AND is_deleted = 0 AND audit_status = 0;",
            f"SELECT COUNT(*) AS images_total FROM tb_content_image WHERE image_url LIKE '%{BATCH}%';",
            f"SELECT audit_status, COUNT(*) cnt FROM tb_content WHERE content LIKE '{BATCH_TAG}%' AND is_deleted = 0 GROUP BY audit_status ORDER BY audit_status;",
            f"SELECT audit_status, COUNT(*) cnt FROM tb_question_answer WHERE content LIKE '{BATCH_TAG}%' AND is_deleted = 0 GROUP BY audit_status ORDER BY audit_status;",
            f"SELECT audit_status, COUNT(*) cnt FROM tb_content_comment WHERE content LIKE '{BATCH_TAG}%' AND is_deleted = 0 GROUP BY audit_status ORDER BY audit_status;",
        ]
    )


def write_rollback_sql() -> str:
    return "\n".join(
        [
            f"-- rollback for {BATCH}",
            "SET NAMES utf8mb4;",
            "START TRANSACTION;",
            "",
            "-- 1) 先删互动",
            "DELETE cl FROM tb_content_like cl JOIN tb_content c ON c.content_id = cl.content_id WHERE c.content LIKE '[seed-batch-20260526]%';",
            "DELETE cc FROM tb_content_collect cc JOIN tb_content c ON c.content_id = cc.content_id WHERE c.content LIKE '[seed-batch-20260526]%';",
            "DELETE al FROM tb_answer_like al JOIN tb_question_answer a ON a.answer_id = al.answer_id WHERE a.content LIKE '[seed-batch-20260526]%';",
            "DELETE cl FROM tb_comment_like cl JOIN tb_content_comment cm ON cm.comment_id = cl.comment_id WHERE cm.content LIKE '[seed-batch-20260526]%';",
            "",
            "-- 2) 删图片",
            "DELETE ci FROM tb_comment_image ci JOIN tb_content_comment cm ON cm.comment_id = ci.comment_id WHERE cm.content LIKE '[seed-batch-20260526]%';",
            "DELETE ai FROM tb_answer_image ai JOIN tb_question_answer a ON a.answer_id = ai.answer_id WHERE a.content LIKE '[seed-batch-20260526]%';",
            "DELETE i FROM tb_content_image i JOIN tb_content c ON c.content_id = i.content_id WHERE c.content LIKE '[seed-batch-20260526]%' OR i.image_url LIKE '%seed-batch-20260526%';",
            "",
            "-- 3) 软删评论/回答/帖子",
            "UPDATE tb_content_comment SET is_deleted = 1, update_time = NOW() WHERE content LIKE '[seed-batch-20260526]%' AND is_deleted = 0;",
            "UPDATE tb_question_answer SET is_deleted = 1, update_time = NOW(), is_accepted = 0 WHERE content LIKE '[seed-batch-20260526]%' AND is_deleted = 0;",
            "UPDATE tb_content SET is_deleted = 1, update_time = NOW() WHERE content LIKE '[seed-batch-20260526]%' AND is_deleted = 0;",
            "",
            "-- 4) 对账",
            "SOURCE src/main/resources/db/dev-sync-counters.sql;",
            "COMMIT;",
        ]
    )


def main() -> None:
    OUT_DB_DIR.mkdir(parents=True, exist_ok=True)
    seed_sql, manifest = write_seed_sql()
    verify_sql = write_verify_sql()
    rollback_sql = write_rollback_sql()

    (OUT_DB_DIR / f"{BATCH}.sql").write_text(seed_sql, encoding="utf-8")
    (OUT_DB_DIR / f"{BATCH}-verify.sql").write_text(verify_sql, encoding="utf-8")
    (OUT_DB_DIR / f"{BATCH}-rollback.sql").write_text(rollback_sql, encoding="utf-8")
    OUT_MANIFEST.write_text(json.dumps(manifest, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"Generated: {OUT_DB_DIR / (BATCH + '.sql')}")
    print(f"Generated: {OUT_DB_DIR / (BATCH + '-verify.sql')}")
    print(f"Generated: {OUT_DB_DIR / (BATCH + '-rollback.sql')}")
    print(f"Generated: {OUT_MANIFEST}")


if __name__ == "__main__":
    main()
