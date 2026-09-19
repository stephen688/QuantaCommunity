"""把 Promptfoo raw 结果合并为不含攻击文本/回复正文的 M4 脱敏汇总。"""

from __future__ import annotations

import argparse
import hashlib
import json
from collections import Counter
from pathlib import Path
from typing import Any

_SECTION_MARKER = "## Promptfoo Red Team"


def _is_grader_error(row: dict[str, Any]) -> bool:
    grading = row.get("gradingResult") or {}
    if "could not extract json" in str(grading.get("reason", "")).lower():
        return True
    return any(
        bool((component.get("metadata") or {}).get("graderError"))
        for component in grading.get("componentResults", [])
    )


def _case_fingerprint(row: dict[str, Any]) -> str:
    """用不可逆指纹匹配复验范围，不把攻击文本写入汇总。"""
    metadata = row.get("metadata") or {}
    material = {
        "plugin": metadata.get("pluginId"),
        "seed": metadata.get("originalText") or (row.get("vars") or {}).get("prompt"),
    }
    encoded = json.dumps(material, ensure_ascii=False, sort_keys=True).encode()
    return hashlib.sha256(encoded).hexdigest()


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
        elif plugin:
            # Promptfoo omits strategyId for the unmodified baseline strategy.
            strategies["basic"] += 1

    successes = int(stats.get("successes", 0))
    failures = int(stats.get("failures", 0))
    errors = int(stats.get("errors", 0))
    grader_errors = sum(1 for row in rows if not row.get("success") and _is_grader_error(row))
    verified_attack_failures = max(failures - grader_errors, 0)
    token_usage = stats.get("tokenUsage") or {}
    grading_usage = token_usage.get("assertions") or {}
    configured_grader = str((raw.get("config") or {}).get("redteam", {}).get("provider", ""))
    expected_grader = f"openai:chat:{model}"
    if configured_grader and configured_grader != expected_grader:
        raise ValueError(
            f"redteam grader model drift: expected={expected_grader!r}, actual={configured_grader!r}"
        )
    return {
        "eval_id": str(raw["evalId"]),
        "git_sha": git_sha,
        "model": model,
        "total_cases": successes + failures + errors,
        "successes": successes,
        "failures": failures,
        "errors": errors,
        "grader_errors": grader_errors,
        "unresolved_failures": grader_errors + errors,
        "successful_attacks": verified_attack_failures,
        "passed": failures == 0 and errors == 0,
        "status": "PASS" if failures == 0 and errors == 0 else "EVALUATION_BLOCKED",
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


def _run_evidence(summary: dict[str, Any]) -> dict[str, Any]:
    return {
        "eval_id": summary["eval_id"],
        "successes": summary["successes"],
        "failures": summary["failures"],
        "errors": summary["errors"],
        "grader_errors": summary["grader_errors"],
        "status": summary["status"],
        "duration_ms": summary["duration_ms"],
        "token_usage": summary["token_usage"],
    }


def build_remediated_summary(
    initial_raw: dict[str, Any],
    targeted_raw: dict[str, Any],
    git_sha: str,
    config_path: Path,
    *,
    verified_transformed_prompt_sha256: str | None = None,
) -> dict[str, Any]:
    """合并首次完整扫描和只覆盖失败项的定向复验，不把 3 条重复算成新攻击。"""
    initial = build_redteam_summary(initial_raw, git_sha, config_path)
    targeted = build_redteam_summary(targeted_raw, git_sha, config_path)
    failed_scope = initial["failures"] + initial["errors"]
    if targeted["total_cases"] != failed_scope:
        raise ValueError(
            "targeted recheck must cover every initial failure/error exactly once: "
            f"expected={failed_scope}, actual={targeted['total_cases']}"
        )
    initial_failed = {
        _case_fingerprint(row)
        for row in initial_raw["results"].get("results", [])
        if not row.get("success")
    }
    targeted_cases = {_case_fingerprint(row) for row in targeted_raw["results"].get("results", [])}
    if initial_failed != targeted_cases:
        raise ValueError("targeted recheck cases do not exactly match initial failures/errors")
    transformed_hashes = {
        hashlib.sha256(str(final_prompt).encode()).hexdigest()
        for row in initial_raw["results"].get("results", [])
        if not row.get("success")
        and (row.get("metadata") or {}).get("pluginId") == "prompt-extraction"
        and (
            final_prompt := (row.get("response") or {})
            .get("metadata", {})
            .get("redteamFinalPrompt")
        )
    }
    transformed_verified = not transformed_hashes or (
        verified_transformed_prompt_sha256 in transformed_hashes
    )
    if not transformed_verified:
        raise ValueError(
            "prompt-extraction failure used a transformed prompt; exact regression proof is required"
        )

    effective_successes = initial["successes"] + targeted["successes"]
    effective_passed = (
        targeted["passed"]
        and effective_successes == initial["total_cases"]
        and transformed_verified
    )
    return {
        **initial,
        "successes": effective_successes,
        "failures": targeted["failures"],
        "errors": targeted["errors"],
        "successful_attacks": targeted["failures"],
        "passed": effective_passed,
        "status": "PASS_WITH_TARGETED_RECHECK" if effective_passed else "EVALUATION_BLOCKED",
        "grader_errors": targeted["grader_errors"],
        "unresolved_failures": targeted["failures"] + targeted["errors"],
        "matched_formal_failures": len(initial_failed),
        "transformed_prompt_regression": {
            "passed": transformed_verified,
            "sha256": verified_transformed_prompt_sha256,
        },
        "duration_ms": initial["duration_ms"] + targeted["duration_ms"],
        "token_usage": {
            key: initial["token_usage"][key] + targeted["token_usage"][key]
            for key in ("target", "grading", "cached")
        },
        "initial_run": _run_evidence(initial),
        "targeted_recheck": _run_evidence(targeted),
    }


def _markdown_section(redteam: dict[str, Any]) -> str:
    status = redteam["status"]
    plugins = ", ".join(f"{key}={value}" for key, value in redteam["plugins"].items())
    strategies = ", ".join(f"{key}={value}" for key, value in redteam["strategies"].items())
    tokens = redteam["token_usage"]
    lines = [
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
        f"- Grader errors: {redteam['grader_errors']}",
        f"- Successful attacks: {redteam['successful_attacks']}",
        f"- Tokens (target/grading/cached): {tokens['target']}/{tokens['grading']}/{tokens['cached']}",
        f"- Plugins: {plugins or '-'}",
        f"- Strategies: {strategies or '-'}",
        f"- Generated config SHA-256: `{redteam['config_sha256']}`",
        "- Raw prompts, replies, errors, account data, and traces are excluded from Git.",
    ]
    if initial := redteam.get("initial_run"):
        lines.extend(
            [
                f"- Initial full scan: `{initial['eval_id']}` "
                f"({initial['successes']} pass / {initial['failures']} fail / "
                f"{initial['errors']} error; grader errors={initial['grader_errors']})",
                f"- Targeted recheck: `{redteam['targeted_recheck']['eval_id']}` "
                f"({redteam['targeted_recheck']['successes']} pass / "
                f"{redteam['targeted_recheck']['failures']} fail / "
                f"{redteam['targeted_recheck']['errors']} error)",
            ]
        )
    lines.append("")
    return "\n".join(lines)


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
    parser.add_argument("--targeted", type=Path)
    parser.add_argument("--verified-transformed-prompt-sha256")
    parser.add_argument("--git-sha", required=True)
    parser.add_argument("--summary-json", type=Path, required=True)
    parser.add_argument("--summary-md", type=Path, required=True)
    args = parser.parse_args()

    raw = json.loads(args.raw.read_text(encoding="utf-8"))
    if args.targeted is not None:
        targeted = json.loads(args.targeted.read_text(encoding="utf-8"))
        redteam = build_remediated_summary(
            raw,
            targeted,
            args.git_sha,
            args.config,
            verified_transformed_prompt_sha256=args.verified_transformed_prompt_sha256,
        )
    else:
        redteam = build_redteam_summary(raw, args.git_sha, args.config)
    merge_summary(args.summary_json, args.summary_md, redteam)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
