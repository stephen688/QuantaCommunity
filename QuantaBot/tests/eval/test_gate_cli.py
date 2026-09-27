"""M4 gate CLI：只允许基础设施故障重试一次。"""

import pytest
from scripts import run_m4_gate
from scripts.run_m4_gate import _run_with_retry
from tests.eval._gate import CaseRunRecord, FailureKind
from tests.eval._runner import CaseResult, load_case

from quanta_bot.infra.settings import Settings
from quanta_bot.pipeline.ports import RunTrace


def _record(failure_kind: FailureKind | None) -> CaseRunRecord:
    return CaseRunRecord(
        case_id="persona-01-course",
        run_number=1,
        decision="failed" if failure_kind else "replied",
        mode="专业答疑",
        p0_results={"decision_is": failure_kind is None},
        p1_score=4 if failure_kind is None else None,
        p2_score=4 if failure_kind is None else None,
        failure_kind=failure_kind,
    )


@pytest.mark.asyncio
async def test_gate_retries_infra_once_then_returns_success() -> None:
    attempts: list[int] = []

    async def operation(attempt: int) -> CaseRunRecord:
        attempts.append(attempt)
        return _record(FailureKind.INFRA_BLOCKED if attempt == 0 else None)

    result = await _run_with_retry(operation, max_infra_retries=1)

    assert result.failure_kind is None
    assert attempts == [0, 1]


@pytest.mark.asyncio
@pytest.mark.parametrize(
    "failure_kind",
    [FailureKind.ASSERTION_FAILED, FailureKind.SAFETY_BLOCKED],
)
async def test_gate_never_retries_capability_or_safety_failure(
    failure_kind: FailureKind,
) -> None:
    attempts = 0

    async def operation(attempt: int) -> CaseRunRecord:
        nonlocal attempts
        attempts += 1
        return _record(failure_kind)

    result = await _run_with_retry(operation, max_infra_retries=1)

    assert result.failure_kind == failure_kind
    assert attempts == 1


@pytest.mark.asyncio
@pytest.mark.parametrize(
    ("validation_failed", "expected_kind", "expected_attempts"),
    [(True, FailureKind.ASSERTION_FAILED, 1), (False, FailureKind.INFRA_BLOCKED, 2)],
)
async def test_gate_classifies_generation_failure_without_retrying_capability(
    monkeypatch: pytest.MonkeyPatch,
    validation_failed: bool,
    expected_kind: FailureKind,
    expected_attempts: int,
) -> None:
    """格式能力失败不刷轮次，外部故障仍仅重试一次，已知费用不能漏报。"""
    case = load_case(run_m4_gate.CASES_DIR / "persona-01-course.yaml")
    trace = RunTrace(
        comment_id=1,
        post_id=1,
        trigger_content="合成测试",
        decision="failed",
        mode="专业答疑",
        reason="生成失败",
        error="合成失败证据",
        prompt_tokens=1000,
        completion_tokens=200,
        generation_usage_complete=validation_failed,
        generation_validation_failed=validation_failed,
    )

    async def failed_case(current_case: object) -> CaseResult:
        return CaseResult(decision="failed", reply="", trace=trace)

    # 隔离付费 case 执行边界；分类器和重试器仍运行真实代码。
    monkeypatch.setattr(run_m4_gate, "run_case", failed_case)
    raw_attempts: list[dict[str, object]] = []

    async def operation(attempt: int) -> CaseRunRecord:
        return await run_m4_gate._execute_case(
            case, 1, attempt, Settings(_env_file=None), raw_attempts
        )

    record = await _run_with_retry(operation, max_infra_retries=1)

    assert record.failure_kind == expected_kind
    assert len(raw_attempts) == expected_attempts
    assert record.estimated_cost_fen == 2
