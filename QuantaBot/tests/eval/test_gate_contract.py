"""M4 gate 契约：冻结集合、稳定性、失败分类和脱敏报告。"""

import json
from datetime import UTC, datetime
from pathlib import Path

import httpx
import pytest
from tests.eval._gate import (
    CaseRunRecord,
    FailureKind,
    GateReport,
    classify_failure,
    compare_runs,
    load_gate_manifest,
    sha256_files,
    write_sanitized_report,
)
from tests.eval._runner import (
    CASES_DIR,
    _CaseCommentTreeFetcher,
    load_case,
    trigger_event_from,
)

from quanta_bot.pipeline.persona import PersonaLibrary
from quanta_bot.pipeline.ports import LLMClientError

PROJECT_ROOT = Path(__file__).resolve().parents[2]
MANIFEST_PATH = PROJECT_ROOT / "eval" / "gate-manifest.yaml"
EXPECTED_PERSONA_CASE_IDS = (
    "persona-01-course",
    "persona-02-compare",
    "persona-03-exam",
    "persona-04-verify",
    "persona-05-summarize",
    "persona-06-policy",
    "persona-07-judge",
    "persona-08-meme",
    "persona-09-supplement",
    "persona-10-roast",
    "persona-11-comfort",
    "persona-12-fail",
    "persona-13-joy",
    "persona-14-injection",
    "persona-15-flamewar",
    "persona-16-offer",
    "persona-17-referral",
)


def test_gate_manifest_freezes_exactly_seventeen_persona_cases() -> None:
    manifest = load_gate_manifest(MANIFEST_PATH)

    assert manifest.version == "m4-v1"
    assert manifest.model == "deepseek-v4-flash"
    assert manifest.case_ids == EXPECTED_PERSONA_CASE_IDS
    assert len(manifest.case_ids) == len(set(manifest.case_ids)) == 17
    assert tuple(case_id.split("-", 2)[1] for case_id in manifest.case_ids) == tuple(
        f"{number:02d}" for number in range(1, 18)
    )
    assert manifest.emotion_case_ids == (
        "persona-11-comfort",
        "persona-12-fail",
        "persona-13-joy",
    )
    assert manifest.fast_gate_ids == ("pipeline-waterline",)
    assert "pipeline-waterline" not in manifest.case_ids


def test_gate_manifest_references_existing_persona_cases_with_valid_thresholds() -> None:
    manifest = load_gate_manifest(MANIFEST_PATH)

    loaded_cases = [load_case(CASES_DIR / f"{case_id}.yaml") for case_id in manifest.case_ids]
    assert all(case.tier == "persona" for case in loaded_cases)
    assert 1 <= manifest.normal_min_score <= 5
    assert 1 <= manifest.emotion_min_score <= 5
    assert 0 <= manifest.judge_score_delta_max <= 4
    assert manifest.max_infra_retries == 1
    assert manifest.required_p0_assertions == (
        "decision_is",
        "mode_is",
        "reply_has_ai_badge",
        "reply_no_secret_leak",
    )


def test_gate_manifest_requires_explicit_p0_assertions_for_every_case() -> None:
    manifest = load_gate_manifest(MANIFEST_PATH)

    for case_id in manifest.case_ids:
        case = load_case(CASES_DIR / f"{case_id}.yaml")
        actual_assertions = {item["assert"] for item in case.deterministic}
        required_assertions = set(manifest.required_p0_assertions)
        required_assertions.update(manifest.case_p0_requirements.get(case_id, ()))
        assert required_assertions <= actual_assertions, (
            f"{case_id} 缺少 P0：{sorted(required_assertions - actual_assertions)}"
        )


def test_sha256_files_is_order_independent_and_content_sensitive(tmp_path: Path) -> None:
    first = tmp_path / "first.txt"
    second = tmp_path / "second.txt"
    first.write_text("alpha", encoding="utf-8")
    second.write_text("beta", encoding="utf-8")

    original = sha256_files([second, first])

    assert original == sha256_files([first, second])
    first.write_text("changed", encoding="utf-8")
    assert original != sha256_files([first, second])


def test_persona_version_changes_when_prompt_content_changes(tmp_path: Path) -> None:
    source_prompts = PROJECT_ROOT / "prompts"
    for source in source_prompts.glob("*.md"):
        (tmp_path / source.name).write_bytes(source.read_bytes())
    original_version = PersonaLibrary(tmp_path).persona_version

    kernel = tmp_path / "kernel.md"
    kernel.write_text(kernel.read_text(encoding="utf-8") + "\n冻结指纹测试", encoding="utf-8")

    assert PersonaLibrary(tmp_path).persona_version != original_version


async def test_frozen_cases_get_a_deterministic_pre_trigger_waterline() -> None:
    """旧 YAML 不伪造线上 ID 单调性；runner 用固定时间表达楼层均早于触发评论。"""
    case = load_case(CASES_DIR / "persona-05-summarize.yaml")
    fetcher = _CaseCommentTreeFetcher(case.trigger)
    event = trigger_event_from(case)

    thread = await fetcher.fetch_context(event)
    floors = await fetcher.fetch_floors(event.post_id)

    trigger_time = datetime.fromisoformat(thread.chain[0].create_time)
    assert floors
    assert all(datetime.fromisoformat(floor.create_time) < trigger_time for floor in floors)


def _run_record(
    *,
    case_id: str = "persona-01-course",
    decision: str = "replied",
    mode: str = "专业答疑",
    p0_results: dict[str, bool] | None = None,
    p1_score: int = 4,
    p2_score: int = 4,
) -> CaseRunRecord:
    return CaseRunRecord(
        case_id=case_id,
        run_number=1,
        decision=decision,
        mode=mode,
        p0_results=p0_results or {"decision_is": True, "mode_is": True},
        p1_score=p1_score,
        p2_score=p2_score,
    )


@pytest.mark.parametrize(
    ("first", "second", "expected_passed"),
    [
        (_run_record(), _run_record(), True),
        (_run_record(decision="replied"), _run_record(decision="silent"), False),
        (_run_record(mode="专业答疑"), _run_record(mode="治理"), False),
        (
            _run_record(p0_results={"decision_is": True}),
            _run_record(p0_results={"decision_is": False}),
            False,
        ),
        (_run_record(p1_score=3), _run_record(p1_score=5), False),
        (_run_record(p1_score=3), _run_record(p1_score=4), True),
        (
            _run_record(case_id="persona-11-comfort", p1_score=4, p2_score=4),
            _run_record(case_id="persona-11-comfort", p1_score=3, p2_score=5),
            False,
        ),
    ],
)
def test_compare_runs_enforces_stability_and_thresholds(
    first: CaseRunRecord, second: CaseRunRecord, expected_passed: bool
) -> None:
    manifest = load_gate_manifest(MANIFEST_PATH)

    assert compare_runs(first, second, manifest).passed is expected_passed


@pytest.mark.parametrize(
    ("error", "assertion_failures", "safety_blocked", "expected"),
    [
        (LLMClientError("timeout"), (), False, FailureKind.INFRA_BLOCKED),
        (httpx.TimeoutException("timeout"), (), False, FailureKind.INFRA_BLOCKED),
        (None, ("persona-01: mode_is 未通过",), False, FailureKind.ASSERTION_FAILED),
        (None, (), True, FailureKind.SAFETY_BLOCKED),
    ],
)
def test_classify_failure(
    error: BaseException | None,
    assertion_failures: tuple[str, ...],
    safety_blocked: bool,
    expected: FailureKind,
) -> None:
    assert (
        classify_failure(
            error=error,
            assertion_failures=assertion_failures,
            safety_blocked=safety_blocked,
        )
        == expected
    )


def test_write_sanitized_report_never_persists_secret_or_raw_reply(tmp_path: Path) -> None:
    report = GateReport(
        git_sha="a" * 40,
        manifest_version="m4-v1",
        case_hash="b" * 64,
        prompt_hash="c" * 64,
        started_at=datetime(2026, 9, 19, tzinfo=UTC),
        runs=(
            _run_record().model_copy(update={"failure_reason": "Bearer test-secret-value-123456"}),
        ),
        total_prompt_tokens=10,
        total_completion_tokens=5,
        estimated_cost_fen=1,
        passed=False,
    )

    json_path, markdown_path = write_sanitized_report(report, tmp_path)
    serialized = json_path.read_text(encoding="utf-8") + markdown_path.read_text(encoding="utf-8")

    assert "test-secret-value" not in serialized
    assert "[已脱敏]" in serialized
    assert json.loads(json_path.read_text(encoding="utf-8"))["schema_version"] == ("m4-report-v1")
