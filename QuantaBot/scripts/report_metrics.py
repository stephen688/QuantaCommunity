"""report_metrics —— 指标基线日报（M5：提交成功率、管线 P95、成本）。

职责：从 SQLite decisions 表聚合当日指标并落 JSON+Markdown 双产物。
口径：提交成功率 = replied / 处理事件日志数；管线 P95 只取 replied 的 duration_ms。
      rejected_moderation 归入 skipped_total。真实审核通过成功率与触发至最终可见延迟
      需要 demo0 终审回传，日报先明确标记待联调。
边界：只读 SQLite；不碰 Langfuse/Redis，也不把缺失的 M5 成本列伪装成已对账的零成本。
用法：uv run python scripts/report_metrics.py --date 2026-09-25
"""

import argparse
import asyncio
import json
import math
import sys
from collections.abc import Mapping, Sequence
from datetime import UTC, datetime
from datetime import date as date_type
from pathlib import Path

import aiosqlite

PROJECT_ROOT = Path(__file__).resolve().parents[1]
SRC_ROOT = PROJECT_ROOT / "src"
if str(SRC_ROOT) not in sys.path:
    sys.path.insert(0, str(SRC_ROOT))

from quanta_bot.infra.settings import Settings, resolve_data_path  # noqa: E402

_REPLIED = "replied"
_SKIPPED_PREFIX = "skipped_"
_REJECTED_MODERATION = "rejected_moderation"
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


def compute_metrics(rows: Sequence[Mapping[str, object]]) -> dict[str, object]:
    """按日报口径聚合决策行；缺失样本通过显式字段暴露。"""
    total = len(rows)
    decision_counts: dict[str, int] = {}
    replied_durations: list[int] = []
    total_cost_li = 0
    missing_cost_count = 0
    for row in rows:
        decision = str(row["decision"])
        decision_counts[decision] = decision_counts.get(decision, 0) + 1
        cost_li = row.get("cost_li")
        if cost_li is None:
            if decision not in _NO_GENERATION_DECISIONS:
                missing_cost_count += 1
        else:
            total_cost_li += int(cost_li)
        if decision == _REPLIED and row.get("duration_ms") is not None:
            replied_durations.append(int(row["duration_ms"]))

    replied = decision_counts.get(_REPLIED, 0)
    p95 = _percentile_95(replied_durations)
    average = round(sum(replied_durations) / len(replied_durations)) if replied_durations else None
    skipped_total = sum(
        count
        for decision, count in decision_counts.items()
        if decision.startswith(_SKIPPED_PREFIX) or decision == _REJECTED_MODERATION
    )
    return {
        "total": total,
        "replied": replied,
        "failed": decision_counts.get("failed", 0) + decision_counts.get("failed_breaker", 0),
        "skipped_total": skipped_total,
        "decision_counts": decision_counts,
        "success_rate": round(replied / total, 4) if total else 0.0,
        "p95_duration_ms": p95,
        "avg_duration_ms": average,
        "duration_sample_count": len(replied_durations),
        "total_cost_li": total_cost_li,
        "missing_cost_count": missing_cost_count,
        "cost_complete": missing_cost_count == 0,
    }


def _percentile_95(values: Sequence[int]) -> int | None:
    """P95 序统计量：ceil(0.95n) 位置的样本值（非插值）。"""
    if not values:
        return None
    ordered = sorted(values)
    index = min(math.ceil(0.95 * len(ordered)), len(ordered)) - 1
    return ordered[index]


async def fetch_rows(db_path: Path, day: date_type) -> list[dict[str, object]]:
    """读取当日决策行；M4 旧库没有 M5 两列时返回显式 None。"""
    async with aiosqlite.connect(db_path) as db:
        cursor = await db.execute("PRAGMA table_info(decisions)")
        columns = {row[1] for row in await cursor.fetchall()}
        if "decision" not in columns or "created_at" not in columns:
            return []
        duration_column = "duration_ms" if "duration_ms" in columns else "NULL"
        cost_column = "cost_li" if "cost_li" in columns else "NULL"
        cursor = await db.execute(
            "SELECT decision, "
            f"{duration_column} AS duration_ms, {cost_column} AS cost_li "
            "FROM decisions WHERE created_at LIKE ? ORDER BY id",
            (f"{day.isoformat()}%",),
        )
        rows = await cursor.fetchall()
    return [{"decision": row[0], "duration_ms": row[1], "cost_li": row[2]} for row in rows]


def write_report(
    metrics: Mapping[str, object], output_dir: Path, day: date_type
) -> tuple[Path, Path]:
    """落 JSON（机器读）与 Markdown（人读）双产物。"""
    output_dir.mkdir(parents=True, exist_ok=True)
    json_path = output_dir / f"metrics-{day.isoformat()}.json"
    markdown_path = output_dir / f"metrics-{day.isoformat()}.md"
    json_path.write_text(
        json.dumps({"date": day.isoformat(), **metrics}, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )
    total = int(metrics["total"])
    replied = int(metrics["replied"])
    p95 = metrics["p95_duration_ms"]
    average = metrics["avg_duration_ms"]
    total_cost_li = int(metrics["total_cost_li"])
    missing_cost_count = int(metrics["missing_cost_count"])
    cost_note = (
        "成本样本完整"
        if bool(metrics["cost_complete"])
        else f"成本样本缺失 {missing_cost_count} 条，合计值不可视为完整对账"
    )
    lines = [
        f"# 指标基线日报 {day.isoformat()}",
        "",
        f"- 处理事件日志数：{total}",
        f"- 提交成功率：{float(metrics['success_rate']):.1%}（replied {replied} / {total}）",
        f"- 管线耗时 P95：{p95 if p95 is not None else 'N/A'} ms",
        f"- 管线平均耗时：{average if average is not None else 'N/A'} ms",
        "- 真实审核通过成功率：待联调验证（demo0 终审回传未接入）",
        "- 触发至最终可见延迟：待联调验证（跨 MQ/终审链路未接入）",
        f"- 当日成本：{total_cost_li} 厘（{total_cost_li / 1000:.2f} 元；{cost_note}）",
        f"- 静默分桶：skipped {int(metrics['skipped_total'])} / failed {int(metrics['failed'])}",
        f"- 决策明细：{metrics['decision_counts']}",
        "",
        "> 举报率口径：被举报 AI 回复 / AI 总回复数——数据在 demo0 侧，人工导出（M5 不动 demo0）。",
    ]
    markdown_path.write_text("\n".join(lines) + "\n", encoding="utf-8")
    return json_path, markdown_path


def _resolve_cli_path(path_value: str | Path) -> Path:
    path = Path(path_value)
    return path if path.is_absolute() else PROJECT_ROOT / path


def _parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description="QuantaBot 指标基线日报（M5 口径）")
    parser.add_argument("--db", default=None, help="SQLite 路径；缺省读取 Settings.audit_db_path")
    parser.add_argument(
        "--date",
        default=datetime.now(UTC).date().isoformat(),
        help="UTC 日期 YYYY-MM-DD",
    )
    parser.add_argument("--output-dir", default=str(PROJECT_ROOT / "eval" / "reports"))
    return parser


def main(argv: Sequence[str] | None = None) -> int:
    """CLI 入口：0=报告完成；2=数据库/配置等基础设施失败。"""
    args = _parser().parse_args(argv)
    try:
        settings = Settings()
        db_path = (
            _resolve_cli_path(args.db) if args.db else resolve_data_path(settings.audit_db_path)
        )
        day = date_type.fromisoformat(args.date)
        rows = asyncio.run(fetch_rows(db_path, day))
        metrics = compute_metrics(rows)
        json_path, markdown_path = write_report(metrics, _resolve_cli_path(args.output_dir), day)
    except Exception as exc:
        print(f"[report_metrics] 基础设施失败：{exc}", file=sys.stderr)
        return 2
    print(
        f"[report_metrics] {day.isoformat()}: submission_rate={metrics['success_rate']:.1%} "
        f"p95={metrics['p95_duration_ms']}ms cost={metrics['total_cost_li']}厘"
    )
    print(f"[report_metrics] 产物：{json_path} / {markdown_path}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
