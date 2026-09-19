"""M4 红队脱敏汇总测试。"""

import json
from pathlib import Path

from scripts.write_m4_redteam_summary import build_redteam_summary, merge_summary


def _raw_result() -> dict:
    return {
        "evalId": "eval-test",
        "results": {
            "stats": {
                "successes": 2,
                "failures": 0,
                "errors": 0,
                "durationMs": 1234,
                "tokenUsage": {
                    "prompt": 10,
                    "completion": 5,
                    "cached": 0,
                    "total": 15,
                    "numRequests": 2,
                    "assertions": {"prompt": 7, "completion": 3, "total": 10},
                },
            },
            "results": [
                {"metadata": {"pluginId": "policy", "strategyId": "basic"}},
                {"metadata": {"pluginId": "pii:direct", "strategyId": "jailbreak"}},
            ],
        },
    }


def test_build_redteam_summary_keeps_only_aggregate_evidence(tmp_path: Path) -> None:
    config = tmp_path / "generated.yaml"
    config.write_text("tests: []\n", encoding="utf-8")

    summary = build_redteam_summary(_raw_result(), "abc123", config)

    assert summary["eval_id"] == "eval-test"
    assert summary["total_cases"] == 2
    assert summary["successful_attacks"] == 0
    assert summary["passed"] is True
    assert summary["plugins"] == {"pii:direct": 1, "policy": 1}
    assert summary["strategies"] == {"basic": 1, "jailbreak": 1}
    serialized = json.dumps(summary, ensure_ascii=False)
    assert "prompt" not in serialized.lower()
    assert "output" not in serialized.lower()


def test_merge_summary_adds_redteam_without_copying_raw_content(tmp_path: Path) -> None:
    summary_json = tmp_path / "m4-v1-summary.json"
    summary_md = tmp_path / "m4-v1-summary.md"
    summary_json.write_text('{"schema_version":"m4-report-v1","passed":true}\n', encoding="utf-8")
    summary_md.write_text("# M4 Gate Summary\n\n- Status: PASS\n", encoding="utf-8")
    redteam = {
        "eval_id": "eval-test",
        "git_sha": "abc123",
        "model": "deepseek-v4-flash",
        "total_cases": 75,
        "successes": 75,
        "failures": 0,
        "errors": 0,
        "successful_attacks": 0,
        "passed": True,
        "duration_ms": 10,
        "token_usage": {"target": 20, "grading": 30, "cached": 0},
        "plugins": {"policy": 15},
        "strategies": {"basic": 25},
        "config_sha256": "deadbeef",
    }

    merge_summary(summary_json, summary_md, redteam)

    assert json.loads(summary_json.read_text(encoding="utf-8"))["redteam"] == redteam
    markdown = summary_md.read_text(encoding="utf-8")
    assert "## Promptfoo Red Team" in markdown
    assert "Successful attacks: 0" in markdown
