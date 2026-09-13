#!/usr/bin/env python3
"""Smoke verification for dev seed: DB checks + API + ES."""
from __future__ import annotations

import json
import os
import sys
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
BASE = os.environ.get("API_BASE", "http://127.0.0.1:9191")
ES = os.environ.get(
    "ES_URL", f"http://{os.environ.get('ES_HOST', '192.168.100.128')}:{os.environ.get('ES_PORT', '9200')}"
)


def http_json(method: str, url: str, body: dict | None = None, token: str | None = None) -> Any:
    data = None
    headers = {"Content-Type": "application/json"}
    if token:
        headers["authorization"] = token
    if body is not None:
        data = json.dumps(body, ensure_ascii=False).encode("utf-8")
    req = request.Request(url, data=data, headers=headers, method=method)
    with request.urlopen(req, timeout=30) as resp:
        return json.loads(resp.read().decode("utf-8"))


def check_db() -> list[tuple[str, bool, str]]:
    results: list[tuple[str, bool, str]] = []
    conn = pymysql.connect(**DB)
    try:
        with conn.cursor(pymysql.cursors.DictCursor) as cur:
            cur.execute(
                """
                SELECT u.id, u.auth_status, ua.audit_status AS auth_audit
                FROM tb_user u
                LEFT JOIN tb_user_auth ua ON ua.user_id = u.id
                WHERE u.id IN (9, 10)
                """
            )
            rows = {r["id"]: r for r in cur.fetchall()}
            ok9 = rows.get(9, {}).get("auth_status") == 1 and rows.get(9, {}).get("auth_audit") == 0
            results.append(("user 9 auth pending", ok9, str(rows.get(9))))
            ok10 = rows.get(10, {}).get("auth_status") == 3 and rows.get(10, {}).get("auth_audit") == 2
            results.append(("user 10 auth rejected", ok10, str(rows.get(10))))

            cur.execute(
                """
                SELECT COUNT(*) AS c FROM tb_question_answer
                WHERE question_id = 4 AND is_deleted = 0 AND audit_status = 1
                """
            )
            approved = int(cur.fetchone()["c"])
            cur.execute(
                """
                SELECT COUNT(*) AS c FROM tb_question_answer
                WHERE question_id = 4 AND is_deleted = 0 AND audit_status = 1 AND is_accepted = 1
                """
            )
            accepted = int(cur.fetchone()["c"])
            ok_ans = approved >= 2 and accepted >= 1
            results.append(
                (
                    "question 4 answers (>=2 approved, 1 accepted)",
                    ok_ans,
                    f"approved={approved}, accepted={accepted}",
                )
            )

            cur.execute("SELECT COUNT(*) AS c FROM tb_notification")
            notif = int(cur.fetchone()["c"])
            results.append(("notifications >= 6", notif >= 6, f"count={notif}"))

            cur.execute(
                "SELECT COUNT(*) AS c FROM tb_content WHERE content_id = 3 AND audit_status = 0 AND is_deleted = 0"
            )
            pending_content = int(cur.fetchone()["c"]) >= 1
            results.append(("content 3 pending audit", pending_content, ""))
    finally:
        conn.close()
    return results


def check_es() -> tuple[str, bool, str]:
    body = {
        "query": {"bool": {"filter": [{"term": {"auditStatus": 1}}, {"term": {"isDeleted": 0}}]}},
        "size": 20,
        "_source": ["contentId"],
    }
    try:
        req = request.Request(
            f"{ES}/content/_search",
            data=json.dumps(body).encode("utf-8"),
            headers={"Content-Type": "application/json"},
            method="POST",
        )
        with request.urlopen(req, timeout=30) as resp:
            data = json.loads(resp.read().decode("utf-8"))
        ids = sorted(int(h["_source"]["contentId"]) for h in data["hits"]["hits"])
        expected = {4, 6, 10, 11, 12, 13, 14}
        missing = expected - set(ids)
        ok = not missing
        return ("ES approved content ids", ok, f"ids={ids}, missing={sorted(missing)}")
    except Exception as e:
        return ("ES approved content ids", False, str(e))


def check_api() -> list[tuple[str, bool, str]]:
    out: list[tuple[str, bool, str]] = []
    try:
        login = http_json("POST", f"{BASE}/user/login", {"code": "test"})
        ok = login.get("code") == 1 and login.get("data", {}).get("id") == 1
        token = (login.get("data") or {}).get("token", "")
        out.append(("POST /user/login code=test", ok, json.dumps(login, ensure_ascii=False)[:200]))
        if not token:
            out.append(("API smoke (needs token)", False, "no token"))
            return out

        answers = http_json("GET", f"{BASE}/answer/list/4", token=token)
        ans_ok = answers.get("code") == 1 and len(answers.get("data") or []) >= 2
        out.append(
            (
                "GET /answer/list/4 (C端回答)",
                ans_ok,
                f"count={len(answers.get('data') or [])}",
            )
        )

        admin_checks = [
            ("GET /admin/content/page?auditStatus=0", f"{BASE}/admin/content/page?pageNum=1&pageSize=5&auditStatus=0"),
            ("GET /admin/identityExam/page?auditStatus=0", f"{BASE}/admin/identityExam/page?pageNum=1&pageSize=5&auditStatus=0"),
            ("GET /admin/answer/page?auditStatus=0", f"{BASE}/admin/answer/page?pageNum=1&pageSize=5&auditStatus=0"),
            ("GET /admin/content/report/page?status=0", f"{BASE}/admin/content/report/page?pageNum=1&pageSize=5&status=0"),
            ("GET /admin/comment/report/page?status=0", f"{BASE}/admin/comment/report/page?pageNum=1&pageSize=5&status=0"),
        ]
        for label, url in admin_checks:
            r = http_json("GET", url, token=token)
            total = (r.get("data") or {}).get("total", 0)
            out.append((label, r.get("code") == 1 and total >= 1, f"code={r.get('code')}, total={total}"))
    except error.HTTPError as e:
        body = e.read().decode("utf-8", errors="replace")
        out.append(("API", False, f"HTTP {e.code}: {body[:300]}"))
    except Exception as e:
        out.append(("API", False, str(e)))
    return out


def main() -> None:
    all_checks = check_db() + [check_es()] + check_api()
    failed = 0
    for name, ok, detail in all_checks:
        status = "PASS" if ok else "FAIL"
        if not ok:
            failed += 1
        print(f"[{status}] {name}" + (f" — {detail}" if detail else ""))
    print(f"\n{len(all_checks) - failed}/{len(all_checks)} passed")
    sys.exit(1 if failed else 0)


if __name__ == "__main__":
    main()
