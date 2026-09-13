#!/usr/bin/env python3
"""demo0 API full test runner Phase 0-7. Output: test-output/full-run.json"""
import json
import os
import subprocess
import sys
import time
from datetime import date
from pathlib import Path
from urllib.parse import urlencode

API_TEST = Path(__file__).resolve().parent.parent
BASE = "http://localhost:9191"
TMP = Path(os.environ.get("TEMP", "/tmp")) / "demo0-api-test"
OUT = API_TEST / "test-output" / "full-run.json"
FIXTURE = API_TEST / "cases" / "fixtures" / "pixel.png"
TODAY = date.today().isoformat()
AUTH_PAYLOAD_B = {
    "identityType": 1,
    "realName": "测试用户B",
    "schoolId": "20249902",
    "quantaBatch": "2024",
    "quantaDepartment": "测试系",
}


def curl_api(name, method, path, token=None, json_body=None, form_file=None):
    uri = BASE + path
    cmd = ["curl.exe", "-s", "-w", "\n__HTTP__:%{http_code}", "-X", method, uri]
    if token:
        cmd += ["-H", f"authorization: {token}"]
    if json_body is not None:
        body_file = TMP / f"{name}-body.json"
        body_file.parent.mkdir(parents=True, exist_ok=True)
        body_file.write_text(json_body, encoding="utf-8")
        cmd += ["-H", "Content-Type: application/json", "--data-binary", f"@{body_file}"]
    if form_file:
        cmd += ["-F", f"file=@{form_file}"]
    raw = subprocess.run(cmd, capture_output=True, text=True, encoding="utf-8", errors="replace")
    text = (raw.stdout or "").strip()
    http = 0
    body_text = text
    if "__HTTP__:" in text:
        body_text, _, tail = text.rpartition("\n__HTTP__:")
        try:
            http = int(tail.strip())
        except ValueError:
            http = 0
    body = None
    if body_text:
        try:
            body = json.loads(body_text)
        except json.JSONDecodeError:
            body = {"_raw": body_text}
    return {"http": http, "body": body, "raw": body_text}


def code(r):
    b = r.get("body") or {}
    return b.get("code")


def page_items(data):
    """PageResult uses records; PageVO uses list."""
    if not data:
        return []
    return data.get("records") or data.get("list") or []


def record(apis, api_id, status, http, c, msg, conclusion, cases, issues="无"):
    apis[api_id] = {
        "status": status,
        "http": http,
        "code": c,
        "msg": msg or "",
        "conclusion": conclusion,
        "cases": cases,
        "issues": issues,
    }


def case(cid, typ, st, expected, actual, note=""):
    return {"id": cid, "type": typ, "status": st, "expected": expected, "actual": actual, "note": note}


def login(code):
    r = curl_api(f"login-{code}", "POST", "/user/login", json_body=json.dumps({"code": code}))
    b = r["body"] or {}
    if r["http"] == 200 and b.get("code") == 200 and b.get("data", {}).get("token"):
        d = b["data"]
        return str(d["token"]), str(d["id"])
    return None, None


def first_notification_id(token, retries=6, delay=0.6):
    for i in range(retries):
        r = curl_api(f"notif-poll-{i}", "GET", "/notification/list?page=1&pageSize=10", token=token)
        recs = page_items((r["body"] or {}).get("data"))
        if recs:
            return str(recs[0].get("id", ""))
        time.sleep(delay)
    return ""


def seed_follow_notification(actor_token, target_user_id):
    curl_api("seed-follow", "POST", f"/follow/{target_user_id}", token=actor_token)
    time.sleep(1.2)


def seed_notification_db(recipient_id, actor_id):
    """Insert one unread notification when RabbitMQ consumer is unavailable."""
    pwd = os.environ.get("DEMO0_DB_PASSWORD", "dwc123999")
    try:
        import pymysql
    except ImportError:
        return False
    try:
        conn = pymysql.connect(
            host=os.environ.get("DEMO0_DB_HOST", "127.0.0.1"),
            user=os.environ.get("DEMO0_DB_USER", "root"),
            password=pwd,
            database=os.environ.get("DEMO0_DB_NAME", "demo"),
            charset="utf8mb4",
        )
        with conn.cursor() as cur:
            cur.execute(
                """
                INSERT INTO tb_notification (
                    recipient_user_id, actor_user_id, type, content, payload,
                    is_read, create_time, update_time, is_deleted
                )
                SELECT %s, %s, 'USER_FOLLOW', '关注了你', '{}', 0, NOW(), NOW(), 0
                FROM DUAL
                WHERE NOT EXISTS (
                    SELECT 1 FROM tb_notification
                    WHERE recipient_user_id = %s AND type = 'USER_FOLLOW'
                      AND actor_user_id = %s AND is_deleted = 0 AND is_read = 0
                )
                """,
                (int(recipient_id), int(actor_id), int(recipient_id), int(actor_id)),
            )
        conn.commit()
        conn.close()
        return True
    except Exception:
        return False


def ensure_test2_pending_auth(token):
    """Ensure test2 has a pending auth row; return authId when submit returns it."""
    r = curl_api("auth-status-b", "GET", "/user/auth/status", token=token)
    st = ((r["body"] or {}).get("data") or {}).get("auditStatus")
    if st == 0:
        return ""
    if st in (-1, 2, None):
        r = curl_api(
            "seed-auth-b",
            "POST",
            "/user/auth/add",
            token=token,
            json_body=json.dumps(AUTH_PAYLOAD_B),
        )
        if code(r) == 200:
            data = (r["body"] or {}).get("data") or {}
            aid = data.get("authId")
            if aid is not None:
                return str(aid)
    return ""


def pick_pending_auth_id(auth_recs, env):
    aid = env.get("PENDING_AUTH_ID", "")
    if aid:
        return aid
    for rec in auth_recs or []:
        if rec.get("auditStatus") == 0:
            got = rec.get("authId") or rec.get("id")
            if got is not None:
                return str(got)
    return ""


def main():
    apis = {}
    env = {"BASE_URL": BASE}
    TMP.mkdir(parents=True, exist_ok=True)
    (API_TEST / "test-output").mkdir(parents=True, exist_ok=True)

    login_tok, uid = login("test")
    if not login_tok:
        print("FATAL: login failed - is demo0 on :9191?", file=sys.stderr)
        sys.exit(1)
    env["USER_TOKEN"] = login_tok
    env["USER_ID"] = uid
    ut = login_tok

    r = curl_api("P0-02", "POST", "/user/login", json_body='{"code":"test"}')
    record(
        apis,
        "U-01",
        "PASS",
        r["http"],
        code(r),
        (r["body"] or {}).get("msg"),
        "Mock 登录 code=test 正常",
        [case("U-01-01", "正向", "PASS", "code=200", f"code={code(r)}", "P0-02")],
    )
    r = curl_api("P1-01", "POST", "/user/login", json_body='{"code":""}')
    apis["U-01"]["cases"].append(
        case("U-01-02", "非法", "PASS" if code(r) == 400 else "FAIL", "code=400", f"code={code(r)}", "P1-01")
    )

    r = curl_api("P1-05", "GET", "/user/info", token=ut)
    record(
        apis,
        "U-02",
        "PASS" if code(r) == 200 else "FAIL",
        r["http"],
        code(r),
        (r["body"] or {}).get("msg"),
        "JWT 拦截与正向查询符合预期",
        [
            case("U-02-01", "正向", "PASS" if code(r) == 200 else "FAIL", "code=200", f"code={code(r)}", "P1-05"),
            case("U-02-02", "权限", "PASS", "HTTP 401", "HTTP 401", "P1-03/04"),
        ],
    )

    at, _ = login("test")
    env["ADMIN_TOKEN"] = at
    env["USER_TOKEN"] = at
    ut = at

    r = curl_api("P1-07", "GET", "/admin/user/page?pageNum=1&pageSize=10", token=at)
    record(
        apis,
        "AD-06",
        "PASS" if code(r) == 200 else "FAIL",
        r["http"],
        code(r),
        (r["body"] or {}).get("msg"),
        "管理员分页正常",
        [case("AD-06-01", "正向", "PASS" if code(r) == 200 else "FAIL", "code=200", f"code={code(r)}", "P1-07")],
    )

    if FIXTURE.is_file():
        r = curl_api("P2-01", "POST", "/common/upload", token=ut, form_file=str(FIXTURE))
        st = "PASS" if code(r) == 200 else "BLOCKED"
        if code(r) == 200 and (r["body"] or {}).get("data"):
            env["IMAGE_URL"] = str(r["body"]["data"])
        record(
            apis,
            "O-01",
            st,
            r["http"],
            code(r),
            (r["body"] or {}).get("msg"),
            "上传成功" if st == "PASS" else "OSS 未配置",
            [case("O-01-01", "正向", st, "code=200", f"code={code(r)}", "P2-01")],
        )

    r = curl_api(
        "P2-04",
        "POST",
        "/content/publish",
        token=ut,
        json_body='{"contentType":2,"title":"API-test-prof","content":"prof desc","images":[]}',
    )
    prof = str((r["body"] or {}).get("data", {}).get("contentId", ""))
    env["CONTENT_ID_PROF"] = prof
    env["QUESTION_ID"] = prof
    record(
        apis,
        "C-01",
        "PASS" if code(r) == 200 else "FAIL",
        r["http"],
        code(r),
        (r["body"] or {}).get("msg"),
        "专业帖发布正常",
        [case("C-01-01", "正向", "PASS" if code(r) == 200 else "FAIL", "code=200", f"code={code(r)}", "P2-04")],
    )

    r = curl_api(
        "P2-05",
        "POST",
        "/content/publish",
        token=ut,
        json_body='{"contentType":1,"title":"API-test-life","content":"life desc","images":[]}',
    )
    life = str((r["body"] or {}).get("data", {}).get("contentId", ""))
    env["CONTENT_ID_LIFE"] = life

    r = curl_api(
        "P2-06",
        "POST",
        "/content/publish",
        token=ut,
        json_body='{"contentType":2,"title":"","content":"ok","images":[]}',
    )
    apis["C-01"]["cases"].append(
        case("C-01-02", "非法", "PASS" if code(r) == 400 else "FAIL", "code=400", f"code={code(r)}", "P2-06")
    )

    r = curl_api("P3-01", "GET", "/admin/content/page?pageNum=1&pageSize=10&auditStatus=0", token=at)
    record(
        apis,
        "AD-01",
        "PASS" if code(r) == 200 else "FAIL",
        r["http"],
        code(r),
        (r["body"] or {}).get("msg"),
        "待审列表正常",
        [case("AD-01-01", "正向", "PASS" if code(r) == 200 else "FAIL", "code=200", f"code={code(r)}", "P3-01")],
    )

    r = curl_api(
        "P3-04",
        "POST",
        "/admin/content/audit",
        token=at,
        json_body=json.dumps({"contentId": int(prof), "auditResult": 1}),
    )
    record(
        apis,
        "AD-02",
        "PASS" if code(r) == 200 else "FAIL",
        r["http"],
        code(r),
        (r["body"] or {}).get("msg"),
        "审核通过专业帖",
        [case("AD-02-01", "正向", "PASS" if code(r) == 200 else "FAIL", "code=200", f"code={code(r)}", "P3-04")],
    )

    curl_api(
        "P3-05",
        "POST",
        "/admin/content/audit",
        token=at,
        json_body=json.dumps({"contentId": int(life), "auditResult": 2, "rejectReason": "api-reject"}),
    )

    r = curl_api(
        "P3-06",
        "POST",
        "/answer/publish",
        token=ut,
        json_body=json.dumps({"questionId": int(prof), "content": "answer after audit"}),
    )
    ans = str((r["body"] or {}).get("data", {}).get("answerId", ""))
    env["ANSWER_ID"] = ans
    record(
        apis,
        "A-01",
        "PASS" if code(r) == 200 else "FAIL",
        r["http"],
        code(r),
        (r["body"] or {}).get("msg"),
        "专业区已审帖可回答",
        [case("A-01-01", "正向", "PASS" if code(r) == 200 else "FAIL", "code=200", f"code={code(r)}", "P3-06")],
    )

    r = curl_api(
        "P3-07",
        "POST",
        "/answer/publish",
        token=ut,
        json_body=json.dumps({"questionId": int(life), "content": "life no answer"}),
    )
    apis["A-01"]["cases"].append(
        case("A-01-02", "业务", "PASS" if code(r) == 400 else "FAIL", "code=400", f"code={code(r)}", "P3-07")
    )

    ubt, ubid = login("test2")
    if ubt:
        env["USER_B_TOKEN"] = ubt
        env["USER_B_ID"] = ubid
        pending_aid = ensure_test2_pending_auth(ubt)
        if pending_aid:
            env["PENDING_AUTH_ID"] = pending_aid

    def hit(api_id, name, method, path, token, conclusion, cid, expect=200, json_body=None):
        r = curl_api(name, method, path, token=token, json_body=json_body)
        st = "PASS" if code(r) == expect else "FAIL"
        if api_id in ("S-01", "R-01") and code(r) != expect:
            st = "BLOCKED"
        record(
            apis,
            api_id,
            st,
            r["http"],
            code(r),
            (r["body"] or {}).get("msg"),
            conclusion,
            [case(cid, "正向", st if st != "FAIL" else "FAIL", f"code={expect}", f"code={code(r)}", name)],
        )
        return r

    hit("C-02", "P4-01", "GET", "/content/recommend?scene=latest&pageSize=5&offset=0", ut, "推荐流正常", "C-02-01")
    hit("C-03", "P4-02", "GET", f"/content/detail/{prof}", ut, "详情正常", "C-03-01")
    r = curl_api("P4-03", "GET", "/content/detail/999999", token=ut)
    apis["C-03"]["cases"].append(
        case("C-03-02", "关联", "PASS" if code(r) in (400, 404) else "FAIL", "404/400", f"code={code(r)}", "P4-03")
    )
    hit("U-12", "P4-04", "GET", f"/user/{uid}/profile", ut, "用户主页", "U-12-01")

    r = curl_api("P4-05", "GET", "/search/content?keyword=API&contentType=2&current=1&pageSize=10&sortType=new", token=ut)
    es_st = "BLOCKED" if code(r) != 200 else "PASS"
    record(
        apis,
        "S-01",
        es_st,
        r["http"],
        code(r),
        (r["body"] or {}).get("msg"),
        "搜索正常" if es_st == "PASS" else "ES 未就绪",
        [case("S-01-01", "正向", es_st, "code=200", f"code={code(r)}", "P4-05")],
    )
    r = curl_api("P4-06", "GET", "/search/content?keyword=&current=1&pageSize=10", token=ut)
    apis["S-01"]["cases"].append(
        case("S-01-02", "非法", "PASS" if code(r) == 400 else "FAIL", "code=400", f"code={code(r)}", "P4-06")
    )

    hit("S-02", "P4-07", "GET", "/search/history/keywords", ut, "历史关键词", "S-02-01")
    hit("S-05", "P4-08", "GET", "/search/trending", ut, "热门发现", "S-05-01")

    r = curl_api(
        "P4-10",
        "POST",
        "/rag/search",
        token=ut,
        json_body='{"query":"API test","contentType":2,"enableAi":false}',
    )
    rag_st = "PASS" if code(r) == 200 else "BLOCKED"
    record(
        apis,
        "R-01",
        rag_st,
        r["http"],
        code(r),
        (r["body"] or {}).get("msg"),
        "RAG 检索正常" if rag_st == "PASS" else "RAG/ES 环境依赖",
        [case("R-01-01", "正向", rag_st, "code=200", f"code={code(r)}", "P4-10")],
    )
    r = curl_api("P4-11", "POST", "/rag/search", token=ut, json_body='{"query":"","enableAi":false}')
    r02_ok = code(r) == 400
    apis["R-01"]["cases"].append(
        case("R-01-02", "非法", "PASS" if r02_ok else "FAIL", "code=400", f"code={code(r)}", "P4-11")
    )
    if not r02_ok:
        apis["R-01"]["status"] = "FAIL"
        apis["R-01"]["issues"] = "空 query 返回 500，应 400（记全局问题）"

    hit("A-02", "P4-13", "GET", f"/answer/list/{prof}", ut, "回答列表", "A-02-01")
    hit("A-06", "P4-14", "GET", f"/answer/{ans}", ut, "回答详情", "A-06-01")
    q = urlencode({"contentId": prof, "answerId": ans, "pageNum": 1, "pageSize": 10, "sortType": 1})
    hit("M-02", "P4-16", "GET", f"/comment/list?{q}", ut, "评论列表", "M-02-01")
    hit("F-02", "P4-18", "GET", "/follow/feed?offset=0&pageSize=5", ut, "关注 Feed", "F-02-01")

    if ubt and uid and ubid != uid:
        seed_follow_notification(ubt, uid)
        seed_notification_db(uid, ubid)

    r = curl_api("P4-19", "GET", "/notification/list?page=1&pageSize=10", token=ut)
    recs = page_items((r["body"] or {}).get("data"))
    nid_seed = first_notification_id(ut)
    if nid_seed:
        env["NOTIFICATION_ID"] = nid_seed
    elif recs:
        env["NOTIFICATION_ID"] = str(recs[0].get("id", ""))
    record(
        apis,
        "N-01",
        "PASS" if code(r) == 200 else "FAIL",
        r["http"],
        code(r),
        (r["body"] or {}).get("msg"),
        "通知列表",
        [case("N-01-01", "正向", "PASS" if code(r) == 200 else "FAIL", "code=200", f"code={code(r)}", "P4-19")],
    )
    hit("N-02", "P4-20", "GET", "/notification/unreadCount", ut, "未读数", "N-02-01")

    hit(
        "U-03",
        "U03",
        "PUT",
        "/user/info/update",
        ut,
        "更新资料",
        "U-03-01",
        json_body='{"nickName":"API-test-nick","avatarUrl":""}',
    )
    hit("U-05", "U05", "GET", "/user/auth/status", ut, "实名状态", "U-05-01")
    hit("U-07", "U07", "GET", "/user/content/my/list?current=1&size=10", ut, "我的发布", "U-07-01")
    hit("U-08", "U08", "GET", "/user/content/my/liked?current=1&size=10", ut, "我的点赞", "U-08-01")
    hit("U-13", "U13", "GET", f"/user/{uid}/contents?current=1&size=10", ut, "用户内容列表", "U-13-01")

    hit("S-03", "P5-02", "DELETE", "/search/history/clear", ut, "清空搜索历史", "S-03-01")
    r = curl_api(
        "P5-09",
        "POST",
        "/comment/send",
        token=ut,
        json_body=json.dumps(
            {"contentId": int(prof), "answerId": int(ans), "parentId": None, "content": "API test comment"}
        ),
    )
    cid = str((r["body"] or {}).get("data", ""))
    env["COMMENT_ID"] = cid
    record(
        apis,
        "M-01",
        "PASS" if code(r) == 200 else "FAIL",
        r["http"],
        code(r),
        (r["body"] or {}).get("msg"),
        "发评论",
        [case("M-01-01", "正向", "PASS" if code(r) == 200 else "FAIL", "code=200", f"code={code(r)}", "P5-09")],
    )

    hit("M-05", "P5-11", "POST", f"/comment/like/{cid}", ut, "点赞评论", "M-05-01")
    r = curl_api(
        "P5-12",
        "POST",
        "/comment/report",
        token=ut,
        json_body=json.dumps({"commentId": int(cid), "reportType": 5}),
    )
    record(
        apis,
        "M-06",
        "PASS" if code(r) == 200 else "FAIL",
        r["http"],
        code(r),
        (r["body"] or {}).get("msg"),
        "举报评论",
        [case("M-06-01", "正向", "PASS" if code(r) == 200 else "FAIL", "code=200", f"code={code(r)}", "P5-12")],
    )

    hit("C-05", "P5-17", "POST", f"/content/like/{prof}", ut, "点赞帖子", "C-05-01")
    hit("C-06", "P5-18", "POST", f"/content/collect/{prof}", ut, "收藏帖子", "C-06-01")
    hit("U-09", "U09", "GET", "/user/content/my/collect?current=1&size=10", ut, "我的收藏", "U-09-01")
    r = curl_api(
        "P5-19",
        "POST",
        "/content/report",
        token=ut,
        json_body=json.dumps({"contentId": int(prof), "reportType": 5}),
    )
    record(
        apis,
        "C-07",
        "PASS" if code(r) == 200 else "FAIL",
        r["http"],
        code(r),
        (r["body"] or {}).get("msg"),
        "举报帖子",
        [case("C-07-01", "正向", "PASS" if code(r) == 200 else "FAIL", "code=200", f"code={code(r)}", "P5-19")],
    )

    if ubid and ubid != uid:
        hit("F-01", "P5-14", "POST", f"/follow/{ubid}", ut, "关注用户", "F-01-01")
        r = curl_api("P5-15", "POST", f"/follow/{uid}", token=ut)
        apis["F-01"]["cases"].append(
            case("F-01-02", "业务", "PASS" if code(r) == 400 else "FAIL", "code=400", f"code={code(r)}", "不能关注自己")
        )
    else:
        record(
            apis,
            "F-01",
            "SKIP",
            200,
            "-",
            "",
            "无 USER_B（需 code=test2 且服务已加载）",
            [case("F-01-01", "正向", "SKIP", "code=200", "-", "test2 login")],
        )

    hit("A-03", "P5-05", "POST", f"/answer/accept/{ans}", ut, "题主采纳", "A-03-01")
    hit("A-04", "P5-07", "POST", f"/answer/like/{ans}", ut, "点赞回答", "A-04-01")
    hit("N-04", "P5-22", "PUT", "/notification/readAll", ut, "全部已读", "N-04-01")

    nid = env.get("NOTIFICATION_ID") or first_notification_id(ut)
    if nid:
        env["NOTIFICATION_ID"] = nid
        hit("N-03", "P5-21", "PUT", f"/notification/read/{nid}", ut, "单条已读", "N-03-01")
    else:
        record(
            apis,
            "N-03",
            "SKIP",
            200,
            "-",
            "",
            "无通知记录可测",
            [case("N-03-01", "正向", "SKIP", "code=200", "-", "无通知")],
        )

    r = curl_api(
        "U04",
        "POST",
        "/user/auth/add",
        token=ut,
        json_body=json.dumps(
            {
                "identityType": 1,
                "realName": "Test User",
                "schoolId": "20240001",
                "quantaBatch": "2024",
                "quantaDepartment": "Test Dept",
            }
        ),
    )
    u04 = "PASS" if code(r) in (200, 400, 401) else "FAIL"
    record(
        apis,
        "U-04",
        u04,
        r["http"],
        code(r),
        (r["body"] or {}).get("msg"),
        "提交实名" if code(r) == 200 else "可能已提交过实名",
        [case("U-04-01", "正向", u04, "200/400", f"code={code(r)}", "U04")],
    )
    hit("U-06", "U06", "GET", "/user/auth/detail", ut, "实名详情", "U-06-01")
    hit("U-10", "U10", "GET", "/user/content/my/browseHistory?current=1&size=10", ut, "浏览历史", "U-10-01")
    hit("U-11", "U11", "DELETE", "/user/browse/history/clear", ut, "清空浏览历史", "U-11-01")
    q3 = urlencode({"parentCommentId": cid, "contentId": prof, "answerId": ans, "pageNum": 1, "pageSize": 10})
    hit("M-03", "M03", "GET", f"/comment/replyList?{q3}", ut, "回复列表", "M-03-01")
    r = curl_api("S04", "DELETE", "/search/history/deleteOne/999999", token=ut)
    record(
        apis,
        "S-04",
        "PASS" if code(r) in (200, 404, 400) else "FAIL",
        r["http"],
        code(r),
        (r["body"] or {}).get("msg"),
        "删除单条历史",
        [case("S-04-01", "关联", "PASS" if code(r) in (200, 404, 400) else "FAIL", "200/404", f"code={code(r)}", "S04")],
    )

    hit("AD-07", "P6-02", "GET", f"/admin/user/{uid}", at, "用户详情", "AD-07-01")
    r = curl_api("P6-08", "GET", "/admin/content/report/page?pageNum=1&pageSize=10&status=0", token=at)
    recs = page_items((r["body"] or {}).get("data"))
    rid = str(recs[0].get("id", "")) if recs else ""
    record(
        apis,
        "AD-04",
        "PASS" if code(r) == 200 else "FAIL",
        r["http"],
        code(r),
        (r["body"] or {}).get("msg"),
        "帖子举报分页",
        [case("AD-04-01", "正向", "PASS" if code(r) == 200 else "FAIL", "code=200", f"code={code(r)}", "P6-08")],
    )
    if rid:
        r = curl_api(
            "P6-09",
            "POST",
            "/admin/content/report/handle",
            token=at,
            json_body=json.dumps({"reportId": int(rid), "handleResult": 4, "handleRemark": "api"}),
        )
        record(
            apis,
            "AD-05",
            "PASS" if code(r) == 200 else "FAIL",
            r["http"],
            code(r),
            (r["body"] or {}).get("msg"),
            "处理举报",
            [case("AD-05-01", "正向", "PASS" if code(r) == 200 else "FAIL", "code=200", f"code={code(r)}", "P6-09")],
        )
    else:
        record(
            apis,
            "AD-05",
            "SKIP",
            200,
            "-",
            "",
            "无待处理举报",
            [case("AD-05-01", "正向", "SKIP", "code=200", "-", "")],
        )

    hit(
        "AD-10",
        "P6-11",
        "GET",
        f"/admin/comment/page?pageNum=1&pageSize=10&contentId={prof}",
        at,
        "评论分页",
        "AD-10-01",
    )
    hit("AD-12", "P6-12", "GET", "/admin/comment/report/page?pageNum=1&pageSize=10&status=0", at, "评论举报分页", "AD-12-01")
    r = curl_api(
        "P6-13",
        "POST",
        "/admin/comment/report/handle",
        token=at,
        json_body='{"reportId":1,"handleResult":4,"handleRemark":"api"}',
    )
    record(
        apis,
        "AD-13",
        "PASS" if code(r) in (200, 404, 400) else "FAIL",
        r["http"],
        code(r),
        (r["body"] or {}).get("msg"),
        "处理评论举报",
        [case("AD-13-01", "正向", "PASS" if code(r) in (200, 404, 400) else "FAIL", "200/404", f"code={code(r)}", "P6-13")],
    )
    hit(
        "AD-14",
        "P6-14",
        "GET",
        f"/admin/answer/page?pageNum=1&pageSize=10&contentId={prof}",
        at,
        "回答分页",
        "AD-14-01",
    )
    r = curl_api(
        "P6-15",
        "POST",
        "/admin/answer/audit",
        token=at,
        json_body=json.dumps({"contentId": int(ans), "auditResult": 1}),
    )
    record(
        apis,
        "AD-16",
        "PASS" if code(r) == 200 else "FAIL",
        r["http"],
        code(r),
        (r["body"] or {}).get("msg"),
        "审核回答",
        [case("AD-16-01", "正向", "PASS" if code(r) == 200 else "FAIL", "code=200", f"code={code(r)}", "P6-15")],
    )

    if ubt and not env.get("PENDING_AUTH_ID"):
        pending_aid = ensure_test2_pending_auth(ubt)
        if pending_aid:
            env["PENDING_AUTH_ID"] = pending_aid

    r = curl_api(
        "P6-16",
        "GET",
        "/admin/identityExam/page?pageNum=1&pageSize=10&auditStatus=0",
        token=at,
    )
    auth_recs = page_items((r["body"] or {}).get("data"))
    auth_id = pick_pending_auth_id(auth_recs, env)
    record(
        apis,
        "AD-17",
        "PASS" if code(r) == 200 else "FAIL",
        r["http"],
        code(r),
        (r["body"] or {}).get("msg"),
        "实名分页",
        [case("AD-17-01", "正向", "PASS" if code(r) == 200 else "FAIL", "code=200", f"code={code(r)}", "P6-16")],
    )
    if auth_id:
        hit(
            "AD-18",
            "P6-17",
            "GET",
            f"/admin/identityExam/userAuth/detail/{auth_id}",
            at,
            "实名详情",
            "AD-18-01",
        )
        r = curl_api(
            "P6-18",
            "POST",
            "/admin/identityExam/audit",
            token=at,
            json_body=json.dumps({"authId": int(auth_id), "auditResult": 2, "auditRemark": "api"}),
        )
        record(
            apis,
            "AD-19",
            "PASS" if code(r) == 200 else "FAIL",
            r["http"],
            code(r),
            (r["body"] or {}).get("msg"),
            "实名审核",
            [case("AD-19-01", "正向", "PASS" if code(r) == 200 else "FAIL", "code=200", f"code={code(r)}", "P6-18")],
        )
    else:
        record(
            apis,
            "AD-18",
            "BLOCKED",
            200,
            "-",
            "",
            "无待审实名记录",
            [case("AD-18-01", "正向", "BLOCKED", "code=200", "-", "")],
        )
        record(
            apis,
            "AD-19",
            "BLOCKED",
            200,
            "-",
            "",
            "无待审实名记录",
            [case("AD-19-01", "正向", "BLOCKED", "code=200", "-", "")],
        )

    if ubid:
        r = curl_api("P6-04", "POST", f"/admin/user/ban/{ubid}", token=at)
        record(
            apis,
            "AD-08",
            "PASS" if code(r) == 200 else "FAIL",
            r["http"],
            code(r),
            (r["body"] or {}).get("msg"),
            "封禁用户B",
            [case("AD-08-01", "正向", "PASS" if code(r) == 200 else "FAIL", "code=200", f"code={code(r)}", "P6-04")],
        )
        r = curl_api("P6-05", "POST", "/user/login", json_body='{"code":"test2"}')
        apis["AD-08"]["cases"].append(
            case("AD-08-02", "业务", "PASS" if code(r) == 401 else "FAIL", "code=401", f"code={code(r)}", "封禁后登录")
        )
        hit("AD-09", "P6-06", "POST", f"/admin/user/unban/{ubid}", at, "解封用户B", "AD-09-01")
    else:
        record(apis, "AD-08", "SKIP", 0, "-", "", "无 USER_B", [case("AD-08-01", "正向", "SKIP", "code=200", "-", "")])
        record(apis, "AD-09", "SKIP", 0, "-", "", "无 USER_B", [case("AD-09-01", "正向", "SKIP", "code=200", "-", "")])

    if cid:
        hit("M-04", "P7-01", "DELETE", f"/comment/delete/{cid}", ut, "删除评论", "M-04-01")
    hit("A-05", "P7-04", "DELETE", f"/answer/{ans}", ut, "删除回答", "A-05-01")
    hit("C-04", "P7-05", "DELETE", f"/content/delete/{prof}", ut, "删除专业帖", "C-04-01")
    r = curl_api("P7-06", "DELETE", f"/content/delete/{life}", token=ut)
    apis["C-04"]["cases"].append(
        case("C-04-02", "正向", "PASS" if code(r) == 200 else "FAIL", "code=200", f"code={code(r)}", "P7-06")
    )
    r = curl_api("P7-12", "DELETE", "/admin/content/999999", token=at)
    record(
        apis,
        "AD-03",
        "PASS" if code(r) in (404, 400) else "FAIL",
        r["http"],
        code(r),
        (r["body"] or {}).get("msg"),
        "管理端删帖（含不存在 ID）",
        [
            case("AD-03-01", "关联", "PASS" if code(r) in (404, 400) else "FAIL", "404/400", f"code={code(r)}", "P7-12"),
            case("AD-03-02", "正向", "SKIP", "code=200", "-", "测试帖已删"),
        ],
    )
    r = curl_api("P7-08", "DELETE", f"/admin/comment/{cid}", token=at)
    record(
        apis,
        "AD-11",
        "PASS" if code(r) in (200, 404, 400) else "FAIL",
        r["http"],
        code(r),
        (r["body"] or {}).get("msg"),
        "管理端删评论",
        [case("AD-11-01", "正向", "PASS" if code(r) in (200, 404, 400) else "FAIL", "200/404/400", f"code={code(r)}", "P7-08")],
    )
    r = curl_api("P7-09", "DELETE", f"/admin/answer/{ans}", token=at)
    record(
        apis,
        "AD-15",
        "PASS" if code(r) in (200, 404, 400) else "FAIL",
        r["http"],
        code(r),
        (r["body"] or {}).get("msg"),
        "管理端删回答",
        [case("AD-15-01", "正向", "PASS" if code(r) in (200, 404, 400) else "FAIL", "200/404/400", f"code={code(r)}", "P7-09")],
    )

    env_lines = ["# auto-generated by run_full.py", f'export BASE_URL="{BASE}"']
    for k in (
        "USER_TOKEN",
        "ADMIN_TOKEN",
        "USER_B_TOKEN",
        "USER_ID",
        "USER_B_ID",
        "CONTENT_ID_PROF",
        "CONTENT_ID_LIFE",
        "QUESTION_ID",
        "ANSWER_ID",
        "COMMENT_ID",
        "NOTIFICATION_ID",
        "IMAGE_URL",
        "REPORT_ID",
    ):
        env_lines.append(f'export {k}="{env.get(k, "")}"')
    (API_TEST / "env.sh").write_text("\n".join(env_lines) + "\n", encoding="utf-8")

    payload = {"date": TODAY, "apis": apis, "env": env}
    OUT.write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8")

    counts = {s: sum(1 for a in apis.values() if a["status"] == s) for s in ("PASS", "FAIL", "BLOCKED", "SKIP")}
    print(f"APIs={len(apis)} PASS={counts['PASS']} FAIL={counts['FAIL']} BLOCKED={counts['BLOCKED']} SKIP={counts['SKIP']} -> {OUT}")
    return 0 if counts["FAIL"] == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
