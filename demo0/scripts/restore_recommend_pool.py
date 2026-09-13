#!/usr/bin/env python3
"""Rebuild Redis recommend pools from approved content in MySQL."""

from __future__ import annotations

import datetime as dt
import math
import os
import sys

import pymysql
from redis import Redis


REDIS_KEYS = [
    "content:recommend:all",
    "content:recommend:life",
    "content:recommend:professional",
    "content:recommend:hot:all",
    "content:recommend:hot:life",
    "content:recommend:hot:professional",
]


def env_int(name: str, default: int) -> int:
    value = os.environ.get(name)
    if value is None or value.strip() == "":
        return default
    try:
        return int(value)
    except ValueError:
        return default


def main() -> int:
    db_host = os.environ.get("DB_HOST", "127.0.0.1")
    db_port = env_int("DB_PORT", 3306)
    db_user = os.environ.get("DB_USER", "root")
    db_pass = os.environ.get("DB_PASSWORD", "dwc123999")
    db_name = os.environ.get("DB_NAME", "demo")

    redis_host = os.environ.get("REDIS_HOST", "127.0.0.1")
    redis_port = env_int("REDIS_PORT", 6379)
    redis_db = env_int("REDIS_DB", 1)
    redis_pass = os.environ.get("REDIS_PASSWORD")

    try:
        db = pymysql.connect(
            host=db_host,
            port=db_port,
            user=db_user,
            password=db_pass,
            database=db_name,
            charset="utf8mb4",
        )
    except Exception as exc:
        print(f"[ERROR] MySQL connect failed: {exc}")
        return 1

    try:
        redis_cli = Redis(
            host=redis_host,
            port=redis_port,
            db=redis_db,
            password=redis_pass if redis_pass else None,
            decode_responses=True,
        )
        redis_cli.ping()
    except Exception as exc:
        print(f"[ERROR] Redis connect failed: {exc}")
        db.close()
        return 1

    try:
        with db.cursor() as cur:
            cur.execute(
                """
                SELECT content_id, content_type, create_time, liked, comment_count, collect_count
                FROM tb_content
                WHERE is_deleted = 0 AND audit_status = 1
                """
            )
            rows = cur.fetchall()

        redis_cli.delete(*REDIS_KEYS)

        now = dt.datetime.now()
        for content_id, content_type, create_time, liked, comment_count, collect_count in rows:
            if create_time is None:
                create_time = now

            sid = str(content_id)
            latest_score = create_time.timestamp() * 1000.0

            redis_cli.zadd("content:recommend:all", {sid: latest_score})
            if content_type == 1:
                redis_cli.zadd("content:recommend:life", {sid: latest_score})
            elif content_type == 2:
                redis_cli.zadd("content:recommend:professional", {sid: latest_score})

            base = (liked or 0) * 3 + (comment_count or 0) * 2 + (collect_count or 0) * 5
            hours = (now - create_time).total_seconds() / 3600.0
            decay = math.pow(hours + 2, 1.5)
            hot_score = (20.0 / decay) if base == 0 else (base / decay)

            redis_cli.zadd("content:recommend:hot:all", {sid: hot_score})
            if content_type == 1:
                redis_cli.zadd("content:recommend:hot:life", {sid: hot_score})
            elif content_type == 2:
                redis_cli.zadd("content:recommend:hot:professional", {sid: hot_score})

        print(f"[OK] rows loaded: {len(rows)}")
        print(f"[OK] {REDIS_KEYS[0]} = {redis_cli.zcard(REDIS_KEYS[0])}")
        print(f"[OK] {REDIS_KEYS[1]} = {redis_cli.zcard(REDIS_KEYS[1])}")
        print(f"[OK] {REDIS_KEYS[2]} = {redis_cli.zcard(REDIS_KEYS[2])}")
        print(f"[OK] {REDIS_KEYS[3]} = {redis_cli.zcard(REDIS_KEYS[3])}")
        print(f"[OK] {REDIS_KEYS[4]} = {redis_cli.zcard(REDIS_KEYS[4])}")
        print(f"[OK] {REDIS_KEYS[5]} = {redis_cli.zcard(REDIS_KEYS[5])}")
        return 0
    except Exception as exc:
        print(f"[ERROR] rebuild failed: {exc}")
        return 1
    finally:
        db.close()


if __name__ == "__main__":
    sys.exit(main())
