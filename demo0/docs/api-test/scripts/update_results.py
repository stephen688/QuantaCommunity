#!/usr/bin/env python3
"""Apply test-output/full-run.json to RESULTS.md"""
import json
import re
from datetime import datetime
from pathlib import Path

API_TEST = Path(__file__).resolve().parent.parent
JSON_PATH = API_TEST / "test-output" / "full-run.json"
RESULTS_PATH = API_TEST / "RESULTS.md"

ALL_IDS = [
    "U-01", "U-02", "U-03", "U-04", "U-05", "U-06", "U-07", "U-08", "U-09", "U-10", "U-11", "U-12", "U-13",
    "C-01", "C-02", "C-03", "C-04", "C-05", "C-06", "C-07",
    "A-01", "A-02", "A-03", "A-04", "A-05", "A-06",
    "M-01", "M-02", "M-03", "M-04", "M-05", "M-06",
    "N-01", "N-02", "N-03", "N-04",
    "S-01", "S-02", "S-03", "S-04", "S-05",
    "F-01", "F-02", "R-01", "O-01",
    "AD-01", "AD-02", "AD-03", "AD-04", "AD-05", "AD-06", "AD-07", "AD-08", "AD-09",
    "AD-10", "AD-11", "AD-12", "AD-13", "AD-14", "AD-15", "AD-16", "AD-17", "AD-18", "AD-19",
]


def format_cases(cases):
    lines = [
        "| 用例ID | 类型 | 状态 | 预期 | 实际 | 备注 |",
        "|--------|------|------|------|------|------|",
    ]
    for c in cases:
        lines.append(
            f"| {c['id']} | {c['type']} | {c['status']} | {c['expected']} | {c['actual']} | {c.get('note', '')} |"
        )
    return "\n".join(lines)


def section_body(api_id: str, api: dict, date: str) -> str:
    st = api["status"]
    http = api.get("http", "—")
    code = api.get("code", "—")
    msg = api.get("msg") or "—"
    conclusion = api.get("conclusion", "—")
    issues = api.get("issues", "无")
    case_table = format_cases(api.get("cases", []))
    return (
        "\n| 项 | 内容 |\n|----|------|\n"
        f"| **接口状态** | `{st}` |\n"
        f"| 最后执行 | {date} |\n"
        f"| HTTP | {http} |\n"
        f"| body.code | {code} |\n"
        f"| body.msg | {msg} |\n"
        f"| **结论** | {conclusion} |\n\n"
        f"#### 用例明细\n\n{case_table}\n\n"
        f"#### 问题与修复\n\n{issues}\n\n"
        f"#### 请求/响应摘录\n\n—\n"
    )


def update_section(md: str, api_id: str, api: dict, date: str) -> str:
    pattern = rf"(### {re.escape(api_id)}[^\n]*)([\s\S]*?)(?=\n### |\n## |\Z)"
    m = re.search(pattern, md)
    if not m:
        print(f"warn: section not found for {api_id}")
        return md
    replacement = m.group(1) + section_body(api_id, api, date)
    return md[: m.start()] + replacement + md[m.end() :]


def update_index_row(md: str, api_id: str, st: str, date: str) -> str:
    """Replace status/date columns until the § link column."""
    pattern = (
        rf"(\| {re.escape(api_id)} \| [^|]+ \| [^|]+ \| )"
        rf".+?"
        rf"( \[§{re.escape(api_id)}\][^\n]*)"
    )
    new_md, n = re.subn(pattern, rf"\1{st} | {date} |\2", md, count=1)
    if n == 0:
        print(f"warn: index row not updated for {api_id}")
    return new_md


def main():
    data = json.loads(JSON_PATH.read_text(encoding="utf-8"))
    apis = data["apis"]
    date = data.get("date", datetime.now().strftime("%Y-%m-%d"))
    md = RESULTS_PATH.read_text(encoding="utf-8")

    for api_id, api in apis.items():
        md = update_section(md, api_id, api, date)

    for api_id in ALL_IDS:
        if api_id not in apis:
            continue
        md = update_index_row(md, api_id, apis[api_id]["status"], date)

    g03_open = "| G-03 | R-01 | `POST /rag/search` 空 query 返回 HTTP 500，预期 400 | 中 | 待修复 |"
    g03_fixed = (
        "| G-03 | R-01 | `POST /rag/search` 空 query 返回 HTTP 500，预期 400 | 中 | "
        "**已修复**（2026-05-19，`GlobalExceptionHandler` 处理 `@Valid` 校验异常→400） |"
    )
    if apis.get("R-01", {}).get("status") == "FAIL":
        if "G-03" not in md:
            md = md.replace("| G-02 | C-01 | 发布默认", f"{g03_open}\n| G-02 | C-01 | 发布默认", 1)
    elif g03_open in md:
        md = md.replace(g03_open, g03_fixed, 1)

    pass_n = sum(1 for a in apis.values() if a["status"] == "PASS")
    fail_n = sum(1 for a in apis.values() if a["status"] == "FAIL")
    blocked_n = sum(1 for a in apis.values() if a["status"] == "BLOCKED")
    skip_n = sum(1 for a in apis.values() if a["status"] == "SKIP")
    pending_n = 64 - pass_n - fail_n - blocked_n - skip_n
    ts = datetime.now().strftime("%Y-%m-%d %H:%M")

    md = re.sub(r"\| PASS \| \d+ \|", f"| PASS | {pass_n} |", md, count=1)
    md = re.sub(r"\| FAIL \| \d+ \|", f"| FAIL | {fail_n} |", md, count=1)
    md = re.sub(r"\| BLOCKED \| \d+ \|", f"| BLOCKED | {blocked_n} |", md, count=1)
    md = re.sub(r"\| SKIP \| \d+ \|", f"| SKIP | {skip_n} |", md, count=1)
    md = re.sub(r"\| PENDING \| \d+ \|", f"| PENDING | {pending_n} |", md, count=1)
    md = re.sub(
        r"\| 最后更新 \| [^|]+ \|",
        f"| 最后更新 | {ts}（Phase 0-7 全量执行 run_full.py） |",
        md,
        count=1,
    )
    md = re.sub(
        r"\| 执行人/Agent \| [^|]+ \|",
        "| 执行人/Agent | Cursor Agent（`scripts/run_full.py`） |",
        md,
        count=1,
    )

    RESULTS_PATH.write_text(md, encoding="utf-8")
    print(f"Updated {RESULTS_PATH}: PASS={pass_n} FAIL={fail_n} BLOCKED={blocked_n} SKIP={skip_n} PENDING={pending_n}")


if __name__ == "__main__":
    main()
