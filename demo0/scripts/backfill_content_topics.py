#!/usr/bin/env python3
"""按内容 ID 游标触发受控主题标签存量回填。

脚本只调用运营接口登记 Outbox，不直连 MySQL、不调用模型；中断后保存最后
一个游标并通过 --after-id 继续。鉴权 Token 从 DEMO0_ADMIN_TOKEN 读取，
不会打印 Token 或帖子正文。
"""
from __future__ import annotations

import argparse
import json
import os
import sys
from urllib import error, request


def enqueue(base_url: str, token: str, after_id: int, limit: int) -> int:
    url = f"{base_url.rstrip('/')}/admin/content/topic-tags/backfill?afterId={after_id}&limit={limit}"
    req = request.Request(
        url,
        headers={"Authorization": f"Bearer {token}"},
        method="POST",
    )
    with request.urlopen(req, timeout=30) as response:
        payload = json.loads(response.read().decode("utf-8"))
    if payload.get("code") != 200:
        raise RuntimeError(f"backfill rejected: code={payload.get('code')}")
    next_cursor = payload.get("data")
    if not isinstance(next_cursor, int):
        raise RuntimeError("backfill response missing numeric cursor")
    return next_cursor


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-url", default="http://127.0.0.1:9191")
    parser.add_argument("--after-id", type=int, default=0)
    parser.add_argument("--limit", type=int, default=10)
    parser.add_argument("--max-batches", type=int, default=1)
    args = parser.parse_args()

    if args.after_id < 0 or args.limit <= 0 or args.max_batches <= 0:
        parser.error("after-id >= 0, limit > 0, max-batches > 0 required")
    token = os.environ.get("DEMO0_ADMIN_TOKEN")
    if not token:
        print("DEMO0_ADMIN_TOKEN is required", file=sys.stderr)
        return 2

    cursor = args.after_id
    for batch in range(args.max_batches):
        try:
            next_cursor = enqueue(args.base_url, token, cursor, args.limit)
        except (OSError, ValueError, RuntimeError) as exc:
            print(f"backfill failed at after_id={cursor}: {exc}", file=sys.stderr)
            return 1
        print(json.dumps({"batch": batch + 1, "afterId": cursor, "nextAfterId": next_cursor}))
        if next_cursor == cursor:
            break
        cursor = next_cursor
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
