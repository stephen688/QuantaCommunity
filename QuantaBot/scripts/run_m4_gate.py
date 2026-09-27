"""M4 Persona/Release 门禁 CLI：冻结模型、一次 infra 重试和脱敏报告。"""

import argparse
import asyncio
import json
import math
import os
import subprocess
import sys
from collections.abc import Awaitable, Callable
from datetime import UTC, datetime
from pathlib import Path

import httpx

PROJECT_ROOT = Path(__file__).resolve().parents[1]
if str(PROJECT_ROOT) not in sys.path:
    sys.path.insert(0, str(PROJECT_ROOT))

from tests.eval._gate import (  # noqa: E402
    CaseRunRecord,
    FailureKind,
    GateReport,
    compare_runs,
    load_gate_manifest,
    sha256_files,
    write_sanitized_report,
)
from tests.eval._runner import (  # noqa: E402
    _ASSERTIONS,
    CASES_DIR,
    EvalCase,
    judge_case,
    load_case,
    run_case,
    verify_case,
)

from quanta_bot.infra.settings import Settings  # noqa: E402
from quanta_bot.pipeline.ports import LLMClientError  # noqa: E402


async def _run_with_retry(
    operation: Callable[[int], Awaitable[CaseRunRecord]],
    *,
    max_infra_retries: int,
) -> CaseRunRecord:
    """基础设施故障最多重试 manifest 次；能力和安全失败原样返回。"""
    for attempt in range(max_infra_retries + 1):
        record = await operation(attempt)
        if record.failure_kind != FailureKind.INFRA_BLOCKED:
            return record
    return record


def _p0_results(case: EvalCase, result: object) -> dict[str, bool]:
    """执行 case 冻结断言并保留各断言结果，供双跑逐项比较。"""
    return {
        spec["assert"]: _ASSERTIONS[spec["assert"]](spec.get("expected"), result)
        for spec in case.deterministic
    }


def _estimated_cost_fen(settings: Settings, prompt_tokens: int, completion_tokens: int) -> int:
    yuan = (
        prompt_tokens * settings.llm_input_price_per_mtok
        + completion_tokens * settings.llm_output_price_per_mtok
    ) / 1_000_000
    return math.ceil(yuan * 100)


async def _execute_case(
    case: EvalCase,
    run_number: int,
    attempt: int,
    settings: Settings,
    raw_attempts: list[dict[str, object]],
) -> CaseRunRecord:
    """执行完整管线，再按 P0→Judge 顺序裁决；P0 失败不会继续花钱刷分。"""
    try:
        result = await run_case(case)
    except (LLMClientError, httpx.HTTPError, TimeoutError) as exc:
        raw_attempts.append(
            {
                "case_id": case.id,
                "run_number": run_number,
                "attempt": attempt,
                "error": repr(exc),
            }
        )
        return CaseRunRecord(
            case_id=case.id,
            run_number=run_number,
            decision="failed",
            failure_kind=FailureKind.INFRA_BLOCKED,
            failure_reason=f"{type(exc).__name__}: {exc}",
        )

    raw_attempts.append(
        {
            "case_id": case.id,
            "run_number": run_number,
            "attempt": attempt,
            "reply": result.reply,
            "trace": result.trace.model_dump(mode="json") if result.trace else None,
        }
    )
    p0_results = _p0_results(case, result)
    p0_failures = verify_case(case, result)
    trace = result.trace
    generation_prompt_tokens = trace.prompt_tokens if trace and trace.prompt_tokens else 0
    generation_completion_tokens = (
        trace.completion_tokens if trace and trace.completion_tokens else 0
    )
    if result.decision == "failed":
        # 格式护栏能力失败不可重跑刷分；网络/供应商失败保留原有一次基础设施重试。
        validation_failed = trace is not None and trace.generation_validation_failed
        return CaseRunRecord(
            case_id=case.id,
            run_number=run_number,
            decision=result.decision,
            mode=trace.mode if trace else None,
            p0_results=p0_results,
            prompt_tokens=generation_prompt_tokens,
            completion_tokens=generation_completion_tokens,
            estimated_cost_fen=_estimated_cost_fen(
                settings, generation_prompt_tokens, generation_completion_tokens
            ),
            failure_kind=(
                FailureKind.ASSERTION_FAILED if validation_failed else FailureKind.INFRA_BLOCKED
            ),
            failure_reason=trace.error if trace else "pipeline failed",
        )
    if p0_failures:
        return CaseRunRecord(
            case_id=case.id,
            run_number=run_number,
            decision=result.decision,
            mode=trace.mode if trace else None,
            p0_results=p0_results,
            prompt_tokens=generation_prompt_tokens,
            completion_tokens=generation_completion_tokens,
            estimated_cost_fen=_estimated_cost_fen(
                settings, generation_prompt_tokens, generation_completion_tokens
            ),
            failure_kind=FailureKind.ASSERTION_FAILED,
            failure_reason="; ".join(p0_failures),
        )

    try:
        judge_result = await judge_case(case, result)
    except (LLMClientError, httpx.HTTPError, TimeoutError) as exc:
        return CaseRunRecord(
            case_id=case.id,
            run_number=run_number,
            decision=result.decision,
            mode=trace.mode if trace else None,
            p0_results=p0_results,
            prompt_tokens=generation_prompt_tokens,
            completion_tokens=generation_completion_tokens,
            estimated_cost_fen=_estimated_cost_fen(
                settings, generation_prompt_tokens, generation_completion_tokens
            ),
            failure_kind=FailureKind.INFRA_BLOCKED,
            failure_reason=f"{type(exc).__name__}: {exc}",
        )
    prompt_tokens = generation_prompt_tokens + judge_result.prompt_tokens
    completion_tokens = generation_completion_tokens + judge_result.completion_tokens
    failure_kind = None
    if judge_result.failure_kind == "INFRA_BLOCKED":
        failure_kind = FailureKind.INFRA_BLOCKED
    elif judge_result.failures:
        failure_kind = FailureKind.ASSERTION_FAILED
    return CaseRunRecord(
        case_id=case.id,
        run_number=run_number,
        decision=result.decision,
        mode=trace.mode if trace else None,
        p0_results=p0_results,
        p1_score=judge_result.verdict.p1.score if judge_result.verdict else None,
        p2_score=judge_result.verdict.p2.score if judge_result.verdict else None,
        prompt_tokens=prompt_tokens,
        completion_tokens=completion_tokens,
        estimated_cost_fen=_estimated_cost_fen(settings, prompt_tokens, completion_tokens),
        failure_kind=failure_kind,
        failure_reason="; ".join(judge_result.failures) or None,
    )


def _git_sha() -> str:
    return subprocess.run(
        ["git", "rev-parse", "HEAD"],
        cwd=PROJECT_ROOT.parent,
        check=True,
        capture_output=True,
        text=True,
    ).stdout.strip()


async def _run_gate(args: argparse.Namespace) -> int:
    manifest_path = (PROJECT_ROOT / args.manifest).resolve()
    output_dir = (PROJECT_ROOT / args.output_dir).resolve()
    manifest = load_gate_manifest(manifest_path)
    settings = Settings()
    if os.getenv("QUANTABOT_EVAL") != "1":
        raise RuntimeError("Persona/Release gate requires QUANTABOT_EVAL=1")
    if settings.deepseek_model != manifest.model:
        raise RuntimeError(
            f"gate model mismatch: settings={settings.deepseek_model}, manifest={manifest.model}"
        )
    if not settings.deepseek_api_key:
        raise RuntimeError("Persona/Release gate requires QUANTABOT_DEEPSEEK_API_KEY")

    started_at = datetime.now(UTC)
    raw_attempts: list[dict[str, object]] = []
    records: list[CaseRunRecord] = []
    cases = [load_case(CASES_DIR / f"{case_id}.yaml") for case_id in manifest.case_ids]
    for run_number in range(1, args.runs + 1):
        for case in cases:

            async def operation(
                attempt: int,
                *,
                current_case: EvalCase = case,
                current_run: int = run_number,
            ) -> CaseRunRecord:
                return await _execute_case(
                    current_case, current_run, attempt, settings, raw_attempts
                )

            records.append(
                await _run_with_retry(operation, max_infra_retries=manifest.max_infra_retries)
            )

    stability_failures: list[str] = []
    if args.runs == 2:
        for case_id in manifest.case_ids:
            case_records = [record for record in records if record.case_id == case_id]
            stability = compare_runs(case_records[0], case_records[1], manifest)
            case_failures = [f"{case_id}: {failure}" for failure in stability.failures]
            stability_failures.extend(case_failures)
            if case_failures:
                second_index = max(
                    index for index, record in enumerate(records) if record.case_id == case_id
                )
                current_reason = records[second_index].failure_reason
                records[second_index] = records[second_index].model_copy(
                    update={
                        "failure_kind": FailureKind.ASSERTION_FAILED,
                        "failure_reason": "; ".join(filter(None, (current_reason, *case_failures))),
                    }
                )

    passed = not stability_failures and all(record.failure_kind is None for record in records)
    total_prompt_tokens = sum(record.prompt_tokens for record in records)
    total_completion_tokens = sum(record.completion_tokens for record in records)
    report = GateReport(
        git_sha=_git_sha(),
        model=manifest.model,
        manifest_version=manifest.version,
        case_hash=sha256_files(
            [Path("eval") / "cases" / f"{case_id}.yaml" for case_id in manifest.case_ids]
        ),
        prompt_hash=sha256_files(sorted(Path("prompts").glob("*.md"))),
        started_at=started_at,
        runs=tuple(records),
        total_prompt_tokens=total_prompt_tokens,
        total_completion_tokens=total_completion_tokens,
        estimated_cost_fen=sum(record.estimated_cost_fen for record in records),
        passed=passed,
    )
    write_sanitized_report(report, output_dir)
    raw_dir = output_dir / "raw"
    raw_dir.mkdir(parents=True, exist_ok=True)
    (raw_dir / f"{manifest.version}-raw.json").write_text(
        json.dumps(
            {"attempts": raw_attempts, "stability_failures": stability_failures},
            ensure_ascii=False,
            indent=2,
        )
        + "\n",
        encoding="utf-8",
    )
    if any(record.failure_kind == FailureKind.INFRA_BLOCKED for record in records):
        return 2
    return 0 if passed else 1


def _parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description="Run the frozen M4 Persona/Release gate.",
        formatter_class=argparse.ArgumentDefaultsHelpFormatter,
    )
    parser.add_argument(
        "--runs",
        type=int,
        choices=(1, 2),
        default=2,
        help="frozen run count; Release uses --runs 2",
    )
    parser.add_argument("--output-dir", default="eval/reports", help="report directory")
    parser.add_argument("--manifest", default="eval/gate-manifest.yaml", help="gate manifest path")
    return parser


def main() -> int:
    return asyncio.run(_run_gate(_parser().parse_args()))


if __name__ == "__main__":
    raise SystemExit(main())
