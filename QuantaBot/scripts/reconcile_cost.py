"""reconcile_cost —— 成本每日对账（M5：Redis 日键 vs Langfuse v4 根观测）。

职责：读取 Redis 日累计，与 Langfuse v4 observations API 中 pipeline.run 根观测的
      metadata.cost_li 求和对比；差异超过容忍度返回 1。
边界：未进入生成的合法静默根观测允许 cost_li=None 并按零生成计；生成状态未知或已生成
      却缺少 cost_li 返回基础设施失败 2，不能把历史未知样本伪装成双零通过。cost_li 是
      QuantaBot 当前生成路径的估算费用，不代表供应商全量账单。
用法：uv run python scripts/reconcile_cost.py --date 2026-09-25 [--tolerance-pct 5]
"""

import argparse
import asyncio
import json
import sys
from collections.abc import Mapping, Sequence
from datetime import UTC, datetime, timedelta
from datetime import date as date_type
from pathlib import Path
from typing import Any

PROJECT_ROOT = Path(__file__).resolve().parents[1]
SRC_ROOT = PROJECT_ROOT / "src"
if str(SRC_ROOT) not in sys.path:
    sys.path.insert(0, str(SRC_ROOT))

from quanta_bot.crosscutting import budget  # noqa: E402
from quanta_bot.infra.kv import RedisKV  # noqa: E402
from quanta_bot.infra.settings import Settings  # noqa: E402

_ROOT_OBSERVATION_NAME = "pipeline.run"
_PAGE_SIZE = 1000
_NO_GENERATION_DECISIONS = frozenset(
    {
        "skipped_not_mentioned",
        "skipped_idempotent",
        "skipped_killswitch",
        "skipped_graylist",
        "skipped_rate_limit",
        "skipped_low_value",
        "skipped_decision",
        "rejected_moderation",
        "failed_breaker",
        "skipped_cost_exhausted",
    }
)


def compare_costs(
    redis_cost_li: int, langfuse_cost_li: int, tolerance_pct: float
) -> dict[str, object]:
    """比较两侧厘值；CLI 另行校验样本与键存在性。"""
    diff_li = abs(redis_cost_li - langfuse_cost_li)
    denominator = max(redis_cost_li, langfuse_cost_li, 1)
    diff_pct = round(diff_li / denominator * 100, 2)
    return {
        "redis_cost_li": redis_cost_li,
        "langfuse_cost_li": langfuse_cost_li,
        "diff_li": diff_li,
        "diff_pct": diff_pct,
        "tolerance_pct": tolerance_pct,
        "within_tolerance": diff_pct <= tolerance_pct,
    }


def _response_data(response: object) -> Sequence[object]:
    if isinstance(response, Mapping):
        data = response.get("data", ())
    elif isinstance(response, Sequence) and not isinstance(response, (str, bytes, bytearray)):
        data = response
    else:
        data = getattr(response, "data", ())
    return data or ()


def _response_cursor(response: object) -> str | None:
    if isinstance(response, Mapping):
        metadata = response.get("meta")
    else:
        metadata = getattr(response, "meta", None)
    if isinstance(metadata, Mapping):
        cursor = metadata.get("cursor") or metadata.get("nextCursor")
    else:
        cursor = getattr(metadata, "cursor", None)
    return str(cursor) if cursor else None


def _observation_value(observation: object, field_name: str) -> object:
    if isinstance(observation, Mapping):
        return observation.get(field_name)
    return getattr(observation, field_name, None)


def _metadata(observation: object) -> Mapping[str, object]:
    raw_metadata = _observation_value(observation, "metadata")
    if isinstance(raw_metadata, Mapping):
        return raw_metadata
    if isinstance(raw_metadata, str):
        try:
            parsed = json.loads(raw_metadata)
        except json.JSONDecodeError:
            return {}
        return parsed if isinstance(parsed, Mapping) else {}
    return {}


def _generation_state(metadata: Mapping[str, object]) -> str:
    """根据根 trace 现有字段判断 generated/no_generation/unknown。"""
    if metadata.get("generated_content") is not None:
        return "generated"
    if metadata.get("decision") == "replied":
        return "generated"
    stage_ms = metadata.get("stage_ms")
    if isinstance(stage_ms, Mapping):
        return "generated" if "generation" in stage_ms else "no_generation"
    if isinstance(stage_ms, str):
        try:
            parsed_stage_ms = json.loads(stage_ms)
        except json.JSONDecodeError:
            parsed_stage_ms = None
        if isinstance(parsed_stage_ms, Mapping):
            return "generated" if "generation" in parsed_stage_ms else "no_generation"
    if metadata.get("decision") in _NO_GENERATION_DECISIONS:
        return "no_generation"
    return "unknown"


def _is_root_pipeline_observation(observation: object) -> bool:
    if _observation_value(observation, "name") != _ROOT_OBSERVATION_NAME:
        return False
    root_marker = _observation_value(observation, "is_root_observation")
    parent_id = _observation_value(observation, "parent_observation_id")
    if root_marker is False:
        return False
    # SDK v4 can mark an app root with a physical parent; is_root_observation wins there.
    if parent_id is not None and root_marker is not True:
        return False
    return True


def fetch_langfuse_cost_stats(client: object, day: date_type) -> dict[str, int]:
    """读取根观测并保留总样本、生成样本和缺失样本统计。"""
    api = getattr(client, "api", None)
    observations_api = getattr(api, "observations", None)
    get_many = getattr(observations_api, "get_many", None)
    if get_many is None:
        raise RuntimeError("Langfuse v4 observations.get_many 不可用")

    from_start_time = datetime(day.year, day.month, day.day, tzinfo=UTC)
    to_start_time = from_start_time + timedelta(days=1)
    total = 0
    sample_count = 0
    generation_sample_count = 0
    no_generation_sample_count = 0
    unknown_state_count = 0
    missing_cost_count = 0
    invalid_cost_count = 0
    unexpected_cost_count = 0
    cursor: str | None = None
    seen_cursors: set[str] = set()
    while True:
        parameters: dict[str, Any] = {
            "fields": "basic,metadata",
            "expand_metadata": "cost_li",
            "limit": _PAGE_SIZE,
            "name": _ROOT_OBSERVATION_NAME,
            "is_root_observation": True,
            "from_start_time": from_start_time,
            "to_start_time": to_start_time,
        }
        if cursor is not None:
            parameters["cursor"] = cursor
        response = get_many(**parameters)
        for observation in _response_data(response):
            if not _is_root_pipeline_observation(observation):
                continue
            sample_count += 1
            metadata = _metadata(observation)
            generation_state = _generation_state(metadata)
            if generation_state == "generated":
                generation_sample_count += 1
            elif generation_state == "no_generation":
                no_generation_sample_count += 1
            else:
                unknown_state_count += 1
            raw_cost = metadata.get("cost_li")
            if raw_cost is None:
                if generation_state != "no_generation":
                    missing_cost_count += 1
                continue
            try:
                cost_li = int(raw_cost)
            except (TypeError, ValueError):
                invalid_cost_count += 1
                continue
            if generation_state == "no_generation" and cost_li != 0:
                unexpected_cost_count += 1
                continue
            total += cost_li
        next_cursor = _response_cursor(response)
        if next_cursor is None:
            break
        if next_cursor in seen_cursors:
            raise RuntimeError("Langfuse observations pagination cursor did not advance")
        seen_cursors.add(next_cursor)
        cursor = next_cursor
    return {
        "total_cost_li": total,
        "sample_count": sample_count,
        "generation_sample_count": generation_sample_count,
        "no_generation_sample_count": no_generation_sample_count,
        "unknown_state_count": unknown_state_count,
        "missing_cost_count": missing_cost_count,
        "invalid_cost_count": invalid_cost_count,
        "unexpected_cost_count": unexpected_cost_count,
    }


def _fetch_langfuse_cost_details(client: object, day: date_type) -> dict[str, int]:
    """兼容旧内部名称，返回带样本状态的 v4 对账统计。"""
    return fetch_langfuse_cost_stats(client, day)


def fetch_langfuse_cost_li(client: object, day: date_type) -> int:
    """遍历当日 Langfuse v4 根 observation 的 metadata.cost_li。"""
    return fetch_langfuse_cost_stats(client, day)["total_cost_li"]


async def _read_redis_cost(settings: Settings, day: date_type) -> int:
    """读取现存 Redis 日键；键不存在视为基础设施失败而非零成本。"""
    kv = RedisKV(settings.redis_url, settings.redis_timeout_seconds)
    try:
        raw_value = await kv.get(budget.cost_key(day))
        if raw_value is None:
            raise RuntimeError(f"Redis 成本键不存在或已过期：{budget.cost_key(day)}")
        try:
            value = int(raw_value)
        except (TypeError, ValueError) as exc:
            raise RuntimeError(f"Redis 成本键不是整数：{raw_value!r}") from exc
        if value < 0:
            raise RuntimeError(f"Redis 成本键为负数：{value}")
        return value
    finally:
        await kv.aclose()


def _parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description="QuantaBot 成本每日对账（Redis vs Langfuse v4）")
    parser.add_argument("--date", default=datetime.now(UTC).date().isoformat())
    parser.add_argument("--tolerance-pct", type=float, default=5.0)
    parser.add_argument("--output-dir", default=str(PROJECT_ROOT / "eval" / "reports"))
    return parser


def main(argv: Sequence[str] | None = None) -> int:
    """CLI 入口：0=对账通过，1=差异超容忍，2=依赖/样本不完整。"""
    args = _parser().parse_args(argv)
    try:
        day = date_type.fromisoformat(args.date)
        settings = Settings()
        redis_cost = asyncio.run(_read_redis_cost(settings, day))
        from langfuse import Langfuse

        client = Langfuse(
            public_key=settings.langfuse_public_key,
            secret_key=settings.langfuse_secret_key,
            host=settings.langfuse_host,
        )
        stats = fetch_langfuse_cost_stats(client, day)
        if stats["sample_count"] == 0:
            raise RuntimeError("Langfuse 当日没有 pipeline.run 根观测，不能以双零通过")
        invalid_sample_count = sum(
            stats[key]
            for key in (
                "unknown_state_count",
                "missing_cost_count",
                "invalid_cost_count",
                "unexpected_cost_count",
            )
        )
        if invalid_sample_count:
            raise RuntimeError(
                "Langfuse 根观测样本无法证明生成成本完整："
                f"unknown={stats['unknown_state_count']}, "
                f"missing={stats['missing_cost_count']}, "
                f"invalid={stats['invalid_cost_count']}, "
                f"unexpected={stats['unexpected_cost_count']}"
            )
        verdict = compare_costs(redis_cost, stats["total_cost_li"], args.tolerance_pct)
        report_path = Path(args.output_dir)
        if not report_path.is_absolute():
            report_path = PROJECT_ROOT / report_path
        report_path = report_path / f"reconcile-{day.isoformat()}.json"
        report_path.parent.mkdir(parents=True, exist_ok=True)
        report_path.write_text(
            json.dumps(
                {
                    "date": day.isoformat(),
                    **verdict,
                    "langfuse_root_observations": stats["sample_count"],
                    **stats,
                    "cost_basis": "QuantaBot generation-path estimate; not supplier full bill",
                },
                ensure_ascii=False,
                indent=2,
            )
            + "\n",
            encoding="utf-8",
        )
    except Exception as exc:
        print(f"[reconcile_cost] 基础设施/样本失败（不假装通过）：{exc}", file=sys.stderr)
        return 2
    status = "PASS" if verdict["within_tolerance"] else "ALERT"
    print(
        f"[reconcile_cost] {status} redis={verdict['redis_cost_li']}厘 "
        f"langfuse={verdict['langfuse_cost_li']}厘 diff={verdict['diff_pct']}% "
        f"（容忍 {args.tolerance_pct}%）→ {report_path}"
    )
    return 0 if verdict["within_tolerance"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
