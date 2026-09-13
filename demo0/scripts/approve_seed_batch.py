#!/usr/bin/env python3
"""Batch approve seed pending items via admin APIs."""
from __future__ import annotations

import argparse
import json
import os
import sys
from dataclasses import dataclass
from typing import Any
from urllib import request

try:
    import pymysql
except ImportError:
    print("pip install pymysql", file=sys.stderr)
    sys.exit(1)

DB = dict(
    host=os.environ.get("DB_HOST", "127.0.0.1"),
    user=os.environ.get("DB_USER", "root"),
    password=os.environ.get("DB_PASSWORD", "dwc123999"),
    database=os.environ.get("DB_NAME", "demo"),
    charset="utf8mb4",
)


@dataclass
class Cfg:
    base: str
    token: str
    dry_run: bool
    reject: bool
    reject_reason: str
    limit: int


def query_ids(sql: str, params: tuple[Any, ...]) -> list[int]:
    conn = pymysql.connect(**DB)
    try:
        with conn.cursor() as cur:
            cur.execute(sql, params)
            return [int(r[0]) for r in cur.fetchall()]
    finally:
        conn.close()


def normalize_token(token: str) -> str:
    token = token.strip()
    if token.lower().startswith("bearer "):
        return token[7:].strip()
    return token


def post_json(url: str, token: str, payload: dict[str, Any]) -> dict[str, Any]:
    data = json.dumps(payload).encode("utf-8")
    req = request.Request(
        url,
        data=data,
        method="POST",
        headers={
            "Content-Type": "application/json",
            # 与 application.yml 中 jwt.user-token-name 一致：authorization（无 Bearer 前缀）
            "authorization": normalize_token(token),
        },
    )
    with request.urlopen(req, timeout=30) as resp:
        return json.loads(resp.read().decode("utf-8"))


def audit_items(cfg: Cfg, endpoint: str, key: str, ids: list[int]) -> tuple[int, int]:
    ok = 0
    fail = 0
    if not ids:
        return (0, 0)
    for item_id in ids:
        payload = {key: item_id, "auditResult": 2 if cfg.reject else 1}
        if cfg.reject and cfg.reject_reason:
            payload["rejectReason"] = cfg.reject_reason
        if cfg.dry_run:
            print(f"DRY {endpoint} {payload}")
            ok += 1
            continue
        try:
            body = post_json(f"{cfg.base}{endpoint}", cfg.token, payload)
            if body.get("code") == 200:
                ok += 1
            else:
                fail += 1
                print(f"fail {endpoint} id={item_id} body={body}", file=sys.stderr)
        except Exception as e:  # noqa: BLE001
            fail += 1
            print(f"fail {endpoint} id={item_id} err={e}", file=sys.stderr)
    return ok, fail


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--base-url", default=os.environ.get("ADMIN_BASE_URL", "http://127.0.0.1:9191"))
    parser.add_argument("--token", default=os.environ.get("ADMIN_TOKEN", ""))
    parser.add_argument("--batch-tag", default=os.environ.get("SEED_BATCH_TAG", "[seed-batch-20260526]"))
    parser.add_argument("--dry-run", action="store_true")
    parser.add_argument("--reject", action="store_true")
    parser.add_argument("--reject-reason", default="seed reject for test")
    parser.add_argument("--limit", type=int, default=2000)
    args = parser.parse_args()

    if not args.dry_run and not args.token:
        raise SystemExit("ADMIN_TOKEN is required unless --dry-run")

    cfg = Cfg(
        base=args.base_url.rstrip("/"),
        token=args.token,
        dry_run=args.dry_run,
        reject=args.reject,
        reject_reason=args.reject_reason,
        limit=args.limit,
    )

    tag_like = args.batch_tag.replace("%", "%%") + "%"
    content_ids = query_ids(
        "SELECT content_id FROM tb_content WHERE is_deleted=0 AND audit_status=0 AND content LIKE %s ORDER BY content_id LIMIT %s",
        (tag_like, cfg.limit),
    )
    answer_ids = query_ids(
        "SELECT answer_id FROM tb_question_answer WHERE is_deleted=0 AND audit_status=0 AND content LIKE %s ORDER BY answer_id LIMIT %s",
        (tag_like, cfg.limit),
    )
    comment_ids = query_ids(
        "SELECT comment_id FROM tb_content_comment WHERE is_deleted=0 AND audit_status=0 AND content LIKE %s ORDER BY comment_id LIMIT %s",
        (tag_like, cfg.limit),
    )

    print(f"pending content={len(content_ids)} answer={len(answer_ids)} comment={len(comment_ids)}")
    c_ok, c_fail = audit_items(cfg, "/admin/content/audit", "contentId", content_ids)
    a_ok, a_fail = audit_items(cfg, "/admin/answer/audit", "answerId", answer_ids)
    m_ok, m_fail = audit_items(cfg, "/admin/comment/audit", "commentId", comment_ids)

    print(
        json.dumps(
            {
                "content": {"ok": c_ok, "fail": c_fail},
                "answer": {"ok": a_ok, "fail": a_fail},
                "comment": {"ok": m_ok, "fail": m_fail},
            },
            ensure_ascii=False,
        )
    )


if __name__ == "__main__":
    main()
