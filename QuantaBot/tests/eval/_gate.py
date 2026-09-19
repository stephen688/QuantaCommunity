"""M4 gate：加载冻结策略并计算内容指纹。

本模块只定义跨 run 的稳定契约；单条 case 的执行和断言仍由 ``_runner`` 负责。
"""

import hashlib
import json
from collections.abc import Sequence
from datetime import datetime
from enum import StrEnum
from pathlib import Path
from typing import Literal

import httpx
import yaml
from pydantic import BaseModel, Field, model_validator

from quanta_bot.crosscutting.leak_scan import sanitize
from quanta_bot.pipeline.ports import LLMClientError


class GateCasePolicy(BaseModel):
    """一条 Persona case 在门禁中的最低分和稳定性策略。"""

    case_id: str
    min_score: int = Field(ge=1, le=5)
    judge_score_delta_max: int = Field(ge=0, le=4)
    emotion: bool = False


class GateManifest(BaseModel):
    """版本控制的 M4 门禁集合与阈值。"""

    version: str
    model: str
    normal_min_score: int = Field(ge=1, le=5)
    emotion_min_score: int = Field(ge=1, le=5)
    judge_score_delta_max: int = Field(ge=0, le=4)
    max_infra_retries: int = Field(ge=0, le=1)
    case_ids: tuple[str, ...]
    emotion_case_ids: tuple[str, ...]
    required_p0_assertions: tuple[str, ...]
    case_p0_requirements: dict[str, tuple[str, ...]] = Field(default_factory=dict)
    fast_gate_ids: tuple[str, ...] = ()

    @model_validator(mode="after")
    def validate_frozen_set(self) -> "GateManifest":
        """拒绝重复、悬空情绪项和非连续的 persona-01～17 集合。"""
        if len(self.case_ids) != 17 or len(set(self.case_ids)) != 17:
            raise ValueError("gate manifest 必须恰好包含 17 个不重复 case")
        expected_prefixes = tuple(f"persona-{number:02d}" for number in range(1, 18))
        actual_prefixes = tuple(case_id.rsplit("-", 1)[0] for case_id in self.case_ids)
        if actual_prefixes != expected_prefixes:
            raise ValueError("gate manifest case 必须按 persona-01～persona-17 连续排列")
        if not set(self.emotion_case_ids).issubset(self.case_ids):
            raise ValueError("emotion_case_ids 必须属于冻结 case 集")
        if len(self.fast_gate_ids) != len(set(self.fast_gate_ids)):
            raise ValueError("fast_gate_ids 不得重复")
        if any(not gate_id.strip() for gate_id in self.fast_gate_ids):
            raise ValueError("fast_gate_ids 不得包含空名称")
        unknown_policy_cases = set(self.case_p0_requirements) - set(self.case_ids)
        if unknown_policy_cases:
            raise ValueError(f"case_p0_requirements 含未冻结 case：{sorted(unknown_policy_cases)}")
        return self

    def policy_for(self, case_id: str) -> GateCasePolicy:
        """返回 case 的冻结阈值；未登记 case 不得进入付费门禁。"""
        if case_id not in self.case_ids:
            raise KeyError(f"case 未登记在 gate manifest：{case_id}")
        emotion = case_id in self.emotion_case_ids
        return GateCasePolicy(
            case_id=case_id,
            min_score=self.emotion_min_score if emotion else self.normal_min_score,
            judge_score_delta_max=self.judge_score_delta_max,
            emotion=emotion,
        )


class FailureKind(StrEnum):
    """门禁失败的稳定分类；基础设施故障不伪装成能力失败。"""

    INFRA_BLOCKED = "INFRA_BLOCKED"
    ASSERTION_FAILED = "ASSERTION_FAILED"
    SAFETY_BLOCKED = "SAFETY_BLOCKED"


class CaseRunRecord(BaseModel):
    """一条 case 的脱敏运行记录；不承载完整回复或 trace。"""

    case_id: str
    run_number: int = Field(ge=1)
    decision: str
    mode: str | None = None
    p0_results: dict[str, bool] = Field(default_factory=dict)
    p1_score: int | None = Field(default=None, ge=1, le=5)
    p2_score: int | None = Field(default=None, ge=1, le=5)
    prompt_tokens: int = Field(default=0, ge=0)
    completion_tokens: int = Field(default=0, ge=0)
    estimated_cost_fen: int = Field(default=0, ge=0)
    failure_kind: FailureKind | None = None
    failure_reason: str | None = None


class StabilityResult(BaseModel):
    """同一冻结 case 两次运行的稳定性裁决。"""

    passed: bool
    failures: tuple[str, ...] = ()


class GateReport(BaseModel):
    """允许提交到 Git 的 M4 脱敏汇总 schema。"""

    schema_version: Literal["m4-report-v1"] = "m4-report-v1"
    git_sha: str
    model: Literal["deepseek-v4-flash"] = "deepseek-v4-flash"
    manifest_version: str
    case_hash: str
    prompt_hash: str
    cache_enabled: Literal[False] = False
    started_at: datetime
    runs: tuple[CaseRunRecord, ...]
    total_prompt_tokens: int = Field(ge=0)
    total_completion_tokens: int = Field(ge=0)
    estimated_cost_fen: int = Field(ge=0)
    passed: bool


def compare_runs(
    first: CaseRunRecord, second: CaseRunRecord, manifest: GateManifest
) -> StabilityResult:
    """校验决策/P0/评分稳定性，并确保两轮各自达到冻结阈值。"""
    failures: list[str] = []
    if first.case_id != second.case_id:
        failures.append("case_id 不一致")
        return StabilityResult(passed=False, failures=tuple(failures))
    policy = manifest.policy_for(first.case_id)
    if first.decision != second.decision:
        failures.append("decision 两轮不一致")
    if first.mode != second.mode:
        failures.append("mode 两轮不一致")
    if first.p0_results != second.p0_results:
        failures.append("P0 结果两轮不一致")
    if not all(first.p0_results.values()) or not all(second.p0_results.values()):
        failures.append("P0 存在失败")
    for score_name in ("p1_score", "p2_score"):
        first_score = getattr(first, score_name)
        second_score = getattr(second, score_name)
        if first_score is None or second_score is None:
            failures.append(f"{score_name} 缺失")
            continue
        if min(first_score, second_score) < policy.min_score:
            failures.append(f"{score_name} 低于阈值 {policy.min_score}")
        if abs(first_score - second_score) > policy.judge_score_delta_max:
            failures.append(f"{score_name} 两轮分差超限")
    return StabilityResult(passed=not failures, failures=tuple(failures))


def classify_failure(
    *,
    error: BaseException | None,
    assertion_failures: Sequence[str],
    safety_blocked: bool,
) -> FailureKind:
    """按可重试语义分类；安全拦截和能力断言都不得自动刷分。"""
    if isinstance(error, (LLMClientError, httpx.TimeoutException)):
        return FailureKind.INFRA_BLOCKED
    if isinstance(error, httpx.HTTPStatusError):
        status_code = error.response.status_code
        if status_code == 429 or status_code >= 500:
            return FailureKind.INFRA_BLOCKED
    if safety_blocked:
        return FailureKind.SAFETY_BLOCKED
    if assertion_failures:
        return FailureKind.ASSERTION_FAILED
    return FailureKind.ASSERTION_FAILED


def _sanitize_reason(reason: str | None) -> str | None:
    if reason is None:
        return None
    return sanitize(reason).content[:240]


def write_sanitized_report(report: GateReport, output_dir: Path) -> tuple[Path, Path]:
    """写入可提交的 JSON/Markdown 汇总；只保留短原因并复用生产脱敏。"""
    output_dir.mkdir(parents=True, exist_ok=True)
    safe_runs = tuple(
        record.model_copy(update={"failure_reason": _sanitize_reason(record.failure_reason)})
        for record in report.runs
    )
    safe_report = report.model_copy(update={"runs": safe_runs})
    json_path = output_dir / f"{report.manifest_version}-summary.json"
    markdown_path = output_dir / f"{report.manifest_version}-summary.md"
    json_path.write_text(
        json.dumps(safe_report.model_dump(mode="json"), ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )
    status = "PASS" if safe_report.passed else "FAIL"
    rows = [
        "# M4 Gate Summary",
        "",
        f"- Status: {status}",
        f"- Git SHA: `{safe_report.git_sha}`",
        f"- Model: `{safe_report.model}`",
        f"- Manifest: `{safe_report.manifest_version}`",
        f"- Runs: {len(safe_report.runs)}",
        f"- Prompt tokens: {safe_report.total_prompt_tokens}",
        f"- Completion tokens: {safe_report.total_completion_tokens}",
        f"- Estimated cost (fen): {safe_report.estimated_cost_fen}",
        "",
        "| Case | Run | P1 | P2 | Failure |",
        "|---|---:|---:|---:|---|",
    ]
    rows.extend(
        "| {case} | {run} | {p1} | {p2} | {failure} |".format(
            case=record.case_id,
            run=record.run_number,
            p1=record.p1_score or "-",
            p2=record.p2_score or "-",
            failure=record.failure_reason or "-",
        )
        for record in safe_report.runs
    )
    markdown_path.write_text("\n".join(rows) + "\n", encoding="utf-8")
    return json_path, markdown_path


def load_gate_manifest(path: Path) -> GateManifest:
    """从 YAML 加载门禁；结构或阈值漂移时立即拒绝。"""
    return GateManifest.model_validate(yaml.safe_load(path.read_text(encoding="utf-8")))


def sha256_files(paths: Sequence[Path]) -> str:
    """按规范化路径排序，对路径和文件内容共同计算稳定 SHA-256。"""
    digest = hashlib.sha256()
    for path in sorted(paths, key=lambda item: item.as_posix()):
        digest.update(path.as_posix().encode("utf-8"))
        digest.update(b"\0")
        digest.update(path.read_bytes())
        digest.update(b"\0")
    return digest.hexdigest()
