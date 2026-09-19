"""把 Promptfoo raw 结果合并为不含攻击文本/回复正文的 M4 脱敏汇总。"""

from __future__ import annotations

import argparse
import hashlib
import json
from collections import Counter
from pathlib import Path
from typing import Any

_SECTION_MARKER = "## Promptfoo Red Team"


def build_redteam_summary(
    raw: dict[str, Any],
    git_sha: str,
    config_path: Path,
    *,
    model: str = "deepseek-v4-flash",
) -> dict[str, Any]:
    """只保留聚合证据；永不复制 prompt、回复、错误正文或 trace。"""
    results = raw["results"]
    stats = results["stats"]
    rows = results.get("results", [])
    plugins: Counter[str] = Counter()
    strategies: Counter[str] = Counter()
    for row in rows:
        metadata = row.get("metadata") or {}
        if plugin := metadata.get("pluginId"):
            plugins[str(plugin)] += 1
        if strategy := metadata.get("strategyId"):
            strategies[str(strategy)] += 1

    successes = int(stats.get("successes", 0))
    failures = int(stats.get("failures", 0))
    errors = int(stats.get("errors", 0))
    token_usage = stats.get("tokenUsage") or {}
    grading_usage = token_usage.get("assertions") or {}
    return {
        "eval_id": str(raw["evalId"]),
        "git_sha": git_sha,
        "model": model,
        "total_cases": successes + failures + errors,
        "successes": successes,
        "failures": failures,
        "errors": errors,
        "successful_attacks": failures,
        "passed": failures == 0 and errors == 0,
        "duration_ms": int(stats.get("durationMs", 0)),
        "token_usage": {
            "target": int(token_usage.get("total", 0)),
            "grading": int(grading_usage.get("total", 0)),
            "cached": int(token_usage.get("cached", 0)) + int(grading_usage.get("cached", 0)),
        },
        "plugins": dict(sorted(plugins.items())),
        "strategies": dict(sorted(strategies.items())),
        "config_sha256": hashlib.sha256(config_path.read_bytes()).hexdigest(),
    }


def _markdown_section(redteam: dict[str, Any]) -> str:
    status = "PASS" if redteam["passed"] else "FAIL"
    plugins = ", ".join(f"{key}={value}" for key, value in redteam["plugins"].items())
    strategies = ", ".join(f"{key}={value}" for key, value in redteam["strategies"].items())
    tokens = redteam["token_usage"]
    return "\n".join(
        [
            _SECTION_MARKER,
            "",
            f"- Status: {status}",
            f"- Eval ID: `{redteam['eval_id']}`",
            f"- Git SHA: `{redteam['git_sha']}`",
            f"- Model: `{redteam['model']}`",
            f"- Cases: {redteam['total_cases']}",
            f"- Passed: {redteam['successes']}",
            f"- Failed: {redteam['failures']}",
            f"- Errors: {redteam['errors']}",
            f"- Successful attacks: {redteam['successful_attacks']}",
            f"- Tokens (target/grading/cached): {tokens['target']}/{tokens['grading']}/{tokens['cached']}",
            f"- Plugins: {plugins or '-'}",
            f"- Strategies: {strategies or '-'}",
            f"- Generated config SHA-256: `{redteam['config_sha256']}`",
            "- Raw prompts, replies, errors, account data, and traces are excluded from Git.",
            "",
        ]
    )


def merge_summary(summary_json: Path, summary_md: Path, redteam: dict[str, Any]) -> None:
    """把红队聚合字段稳定写入现有 Persona 汇总。"""
    payload = json.loads(summary_json.read_text(encoding="utf-8"))
    payload["redteam"] = redteam
    summary_json.write_text(
        json.dumps(payload, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )

    markdown = summary_md.read_text(encoding="utf-8")
    if _SECTION_MARKER in markdown:
        markdown = markdown.split(_SECTION_MARKER, 1)[0].rstrip() + "\n"
    summary_md.write_text(
        markdown.rstrip() + "\n\n" + _markdown_section(redteam),
        encoding="utf-8",
    )


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--raw", type=Path, required=True)
    parser.add_argument("--config", type=Path, required=True)
    parser.add_argument("--git-sha", required=True)
    parser.add_argument("--summary-json", type=Path, required=True)
    parser.add_argument("--summary-md", type=Path, required=True)
    args = parser.parse_args()

    raw = json.loads(args.raw.read_text(encoding="utf-8"))
    redteam = build_redteam_summary(raw, args.git_sha, args.config)
    merge_summary(args.summary_json, args.summary_md, redteam)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
