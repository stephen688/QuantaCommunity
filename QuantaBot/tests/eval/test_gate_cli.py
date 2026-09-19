"""M4 gate CLI：只允许基础设施故障重试一次。"""

import pytest
from scripts.run_m4_gate import _run_with_retry
from tests.eval._gate import CaseRunRecord, FailureKind


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
