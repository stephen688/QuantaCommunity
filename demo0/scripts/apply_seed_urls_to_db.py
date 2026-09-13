#!/usr/bin/env python3
"""Apply seed-oss-urls.json to MySQL demo database."""
import json
import os
import sys
from pathlib import Path

try:
    import pymysql
except ImportError:
    print("pip install pymysql", file=sys.stderr)
    sys.exit(1)

JSON_PATH = Path(__file__).resolve().parent / "seed-oss-urls.json"
DB = dict(
    host=os.environ.get("DB_HOST", "127.0.0.1"),
    user=os.environ.get("DB_USER", "root"),
    password=os.environ.get("DB_PASSWORD", "dwc123999"),
    database=os.environ.get("DB_NAME", "demo"),
    charset="utf8mb4",
)


def with_cache_bust(url: str, version: str) -> str:
    """Append query so mini program / browser fetch new bytes after OSS overwrite."""
    sep = "&" if "?" in url else "?"
    return f"{url}{sep}v={version}"


def main() -> None:
    data = json.loads(JSON_PATH.read_text(encoding="utf-8"))
    version = os.environ.get("SEED_CACHE_VERSION", "realistic2")
    conn = pymysql.connect(**DB)
    try:
        with conn.cursor() as cur:
            for uid, url in data["avatars"].items():
                cur.execute(
                    "UPDATE tb_user SET avatar_url=%s, update_time=NOW() WHERE id=%s",
                    (with_cache_bust(url, version), int(uid)),
                )
                print(f"avatar user {uid}")

            for cid, url in data["posts"].items():
                cid = int(cid)
                cur.execute(
                    "DELETE FROM tb_content_image WHERE content_id=%s AND image_url LIKE %s",
                    (cid, "%dev-seed/posts%"),
                )
                cur.execute(
                    "INSERT INTO tb_content_image (content_id, image_url, sort) VALUES (%s, %s, 0)",
                    (cid, with_cache_bust(url, version)),
                )
                print(f"post image content {cid}")

            # comment on content 6 — reuse post-6 cover as comment attachment for联调
            c6_url = data["posts"].get("6")
            if c6_url:
                cur.execute(
                    "DELETE FROM tb_comment_image WHERE comment_id=3 AND image_url LIKE %s",
                    ("%dev-seed%",),
                )
                cur.execute(
                    "INSERT INTO tb_comment_image (comment_id, image_url, sort) VALUES (3, %s, 0)",
                    (with_cache_bust(c6_url, version),),
                )
                print("comment image comment 3")
        conn.commit()
    finally:
        conn.close()
    print("Done.")


if __name__ == "__main__":
    main()
