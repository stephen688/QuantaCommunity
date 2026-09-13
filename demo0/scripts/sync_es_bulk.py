#!/usr/bin/env python3
"""Bulk upsert approved tb_content rows into Elasticsearch (content index).

Mirrors ElasticSearchServiceImpl.upsertBatchByContentIds field mapping.
Default IDs: 4,6,10-14 (all audit_status=1, is_deleted=0 in dev seed).
"""
from __future__ import annotations

import json
import os
import sys
from pathlib import Path
from typing import Any
from urllib import error, request

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
ES_HOST = os.environ.get("ES_HOST", "192.168.100.128")
ES_PORT = os.environ.get("ES_PORT", "9200")
ES_SCHEME = os.environ.get("ES_SCHEME", "http")
INDEX = "content"
DEFAULT_IDS = [4, 6, 10, 11, 12, 13, 14]


def fetch_rows(content_ids: list[int]) -> list[dict[str, Any]]:
    placeholders = ",".join(["%s"] * len(content_ids))
    sql = f"""
        SELECT content_id, content_type, title, content, publish_user_id,
               audit_status, liked, collect_count, comment_count,
               create_time, is_deleted
        FROM tb_content
        WHERE content_id IN ({placeholders})
    """
    conn = pymysql.connect(**DB)
    try:
        with conn.cursor(pymysql.cursors.DictCursor) as cur:
            cur.execute(sql, content_ids)
            return list(cur.fetchall())
    finally:
        conn.close()


def fetch_all_approved_ids() -> list[int]:
    conn = pymysql.connect(**DB)
    try:
        with conn.cursor() as cur:
            cur.execute(
                """
                SELECT content_id FROM tb_content
                WHERE audit_status = 1 AND is_deleted = 0
                ORDER BY content_id
                """
            )
            return [int(r[0]) for r in cur.fetchall()]
    finally:
        conn.close()


def to_doc(row: dict[str, Any]) -> dict[str, Any]:
    create_time = row["create_time"]
    if hasattr(create_time, "isoformat"):
        create_time = create_time.strftime("%Y-%m-%dT%H:%M:%S")
    return {
        "contentId": row["content_id"],
        "contentType": row["content_type"],
        "title": row["title"] or "",
        "content": row["content"] or "",
        "publishUserId": row["publish_user_id"],
        "auditStatus": row["audit_status"],
        "liked": row["liked"] or 0,
        "collectCount": row["collect_count"] or 0,
        "commentCount": row["comment_count"] or 0,
        "createTime": create_time,
        "isDeleted": row["is_deleted"],
    }


def bulk_upsert(docs: list[dict[str, Any]]) -> dict[str, Any]:
    if not docs:
        return {"upserted": 0, "errors": False}

    lines: list[str] = []
    for doc in docs:
        cid = doc["contentId"]
        lines.append(json.dumps({"index": {"_index": INDEX, "_id": str(cid)}}, ensure_ascii=False))
        lines.append(json.dumps(doc, ensure_ascii=False))
    body = "\n".join(lines) + "\n"
    url = f"{ES_SCHEME}://{ES_HOST}:{ES_PORT}/_bulk"
    req = request.Request(
        url,
        data=body.encode("utf-8"),
        headers={"Content-Type": "application/x-ndjson"},
        method="POST",
    )
    with request.urlopen(req, timeout=60) as resp:
        result = json.loads(resp.read().decode("utf-8"))
    if result.get("errors"):
        failed = [it for it in result.get("items", []) if "error" in it.get("index", {})]
        raise RuntimeError(f"ES bulk errors: {json.dumps(failed[:3], ensure_ascii=False)}")
    return {"upserted": len(docs), "errors": False, "took": result.get("took")}


def main() -> None:
    if "--all-approved" in sys.argv:
        content_ids = fetch_all_approved_ids()
    else:
        extra = [int(a) for a in sys.argv[1:] if a.isdigit()]
        content_ids = extra if extra else DEFAULT_IDS

    rows = fetch_rows(content_ids)
    found = {int(r["content_id"]) for r in rows}
    missing = [i for i in content_ids if i not in found]
    if missing:
        print(f"warn: not in MySQL: {missing}", file=sys.stderr)

    docs = []
    skipped = []
    for row in rows:
        if row["is_deleted"] != 0 or row["audit_status"] != 1:
            skipped.append(int(row["content_id"]))
            continue
        docs.append(to_doc(row))

    if skipped:
        print(f"skip (not indexable): {skipped}", file=sys.stderr)

    print(f"bulk upsert {len(docs)} docs -> {ES_SCHEME}://{ES_HOST}:{ES_PORT}/{INDEX}")
    print(f"content_ids: {[d['contentId'] for d in docs]}")
    try:
        result = bulk_upsert(docs)
    except error.URLError as e:
        print(f"ES unreachable: {e}", file=sys.stderr)
        sys.exit(2)
    print(json.dumps(result, ensure_ascii=False))


if __name__ == "__main__":
    main()
