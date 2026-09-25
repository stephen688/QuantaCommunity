"""M4 付费评测触发器的路径分类契约测试。"""

from __future__ import annotations

import subprocess
import sys
from pathlib import Path

import pytest
from scripts.check_m4_trigger import classify_paths, determine_persona_required


@pytest.mark.parametrize(
    "changed_path",
    [
        "prompts/kernel.md",
        "prompts/mode_answer.md",
        "src/quanta_bot/pipeline/decision.py",
        "src/quanta_bot/crosscutting/moderation.py",
        "src/quanta_bot/crosscutting/idempotency.py",
        "src/quanta_bot/pipeline/context.py",
        "src/quanta_bot/memory/dialogue.py",
        "eval/cases/persona-01-course.yaml",
        "tests/eval/test_gate_contract.py",
    ],
)
def test_sensitive_m4_path_requires_persona_gate(changed_path: str) -> None:
    assert classify_paths((changed_path,)) is True


def test_documentation_only_change_stays_on_fast_gate() -> None:
    assert classify_paths(("docs/README.md", "QuantaBot/docs/plans/M4.md")) is False


def test_unknown_quanta_bot_python_change_is_conservative() -> None:
    assert classify_paths(("src/quanta_bot/server.py",)) is True


def test_missing_base_sha_is_conservative() -> None:
    assert determine_persona_required(None, ("docs/README.md",)) is True


def test_cli_reports_boolean_for_a_real_git_range() -> None:
    script_path = Path(__file__).parents[2] / "scripts" / "check_m4_trigger.py"
    result = subprocess.run(
        [sys.executable, str(script_path), "HEAD~1", "HEAD"],
        cwd=script_path.parents[1],
        capture_output=True,
        check=False,
        text=True,
    )

    assert result.returncode == 0, result.stderr
    assert result.stdout.strip() in {"persona_required=true", "persona_required=false"}
