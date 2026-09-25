"""M4 红队脱敏汇总测试。"""

import hashlib
import json
from pathlib import Path

from scripts.write_m4_redteam_summary import (
    build_redteam_summary,
    build_remediated_summary,
    merge_summary,
)


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
                {
                    "success": True,
                    "vars": {"prompt": "case-a"},
                    "metadata": {"pluginId": "policy"},
                },
                {
                    "success": True,
                    "vars": {"prompt": "case-b"},
                    "metadata": {"pluginId": "pii:direct", "strategyId": "jailbreak"},
                },
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


def test_grader_parse_failure_is_not_counted_as_a_successful_attack(tmp_path: Path) -> None:
    config = tmp_path / "generated.yaml"
    config.write_text("tests: []\n", encoding="utf-8")
    raw = _raw_result()
    raw["results"]["stats"].update(successes=1, failures=1, errors=0)
    raw["results"]["results"][1].update(
        success=False,
        gradingResult={
            "reason": "Could not extract JSON from llm-rubric response",
            "componentResults": [{"metadata": {"graderError": True}}],
        },
    )

    summary = build_redteam_summary(raw, "abc123", config)

    assert summary["grader_errors"] == 1
    assert summary["unresolved_failures"] == 1
    assert summary["successful_attacks"] == 0
    assert summary["passed"] is False


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
        "grader_errors": 0,
        "successful_attacks": 0,
        "passed": True,
        "status": "PASS",
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


def test_remediated_summary_preserves_initial_run_and_targeted_recheck(
    tmp_path: Path,
) -> None:
    config = tmp_path / "generated.yaml"
    config.write_text("tests: []\n", encoding="utf-8")
    initial = _raw_result()
    failed_rows = []
    for index, plugin in enumerate(("policy", "prompt-extraction", "pii:social")):
        failed_rows.append(
            {
                "success": False,
                "vars": {"prompt": f"failed-case-{index}"},
                "metadata": {"pluginId": plugin},
                "gradingResult": {
                    "reason": "Could not extract JSON from llm-rubric response",
                    "componentResults": [{"metadata": {"graderError": True}}],
                },
                "response": {
                    "metadata": {
                        "redteamFinalPrompt": "transformed-extraction"
                        if plugin == "prompt-extraction"
                        else None
                    }
                },
            }
        )
    initial["results"]["results"] = [
        {"success": True, "vars": {"prompt": "passed"}, "metadata": {"pluginId": "policy"}},
        *failed_rows,
    ]
    initial["results"]["stats"].update(successes=72, failures=3, errors=0)
    targeted = _raw_result()
    targeted["evalId"] = "eval-targeted"
    targeted["results"]["results"] = [
        {**row, "success": True, "gradingResult": {}} for row in failed_rows
    ]
    targeted["results"]["stats"].update(successes=3, failures=0, errors=0)

    transformed_hash = hashlib.sha256("transformed-extraction".encode()).hexdigest()
    summary = build_remediated_summary(
        initial,
        targeted,
        "fix-sha",
        config,
        verified_transformed_prompt_sha256=transformed_hash,
    )

    assert summary["total_cases"] == 75
    assert summary["successes"] == 75
    assert summary["failures"] == 0
    assert summary["successful_attacks"] == 0
    assert summary["passed"] is True
    assert summary["status"] == "PASS_WITH_TARGETED_RECHECK"
    assert summary["initial_run"]["eval_id"] == "eval-test"
    assert summary["initial_run"]["failures"] == 3
    assert summary["initial_run"]["grader_errors"] == 3
    assert summary["targeted_recheck"]["eval_id"] == "eval-targeted"
    assert summary["matched_formal_failures"] == 3
    assert summary["transformed_prompt_regression"]["passed"] is True
