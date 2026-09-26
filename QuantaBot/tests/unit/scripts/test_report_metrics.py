"""report_metrics 单测：诚实分桶、P95、旧 schema 读取和产物落盘。"""

import sys
from datetime import date
from pathlib import Path

import aiosqlite
import pytest

PROJECT_ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(PROJECT_ROOT / "scripts"))

from report_metrics import compute_metrics, fetch_rows, write_report  # noqa: E402


def _row(decision: str, duration_ms: int | None, cost_li: int | None = None) -> dict:
    return {"decision": decision, "duration_ms": duration_ms, "cost_li": cost_li}


def test_compute_metrics_counts_moderation_rejection_as_skipped() -> None:
    """审核拒绝属于静默分桶，不能从 skipped_total 漏掉。"""
    rows = [
        _row("replied", 100, 10),
        _row("replied", 200, 20),
        _row("failed", 9000),
        _row("skipped_low_value", 5),
        _row("rejected_moderation", 8),
    ]
    metrics = compute_metrics(rows)
    assert metrics["total"] == 5
    assert metrics["replied"] == 2
    assert metrics["success_rate"] == pytest.approx(0.4)
    assert metrics["failed"] == 1
    assert metrics["skipped_total"] == 2
    assert metrics["total_cost_li"] == 30
    assert metrics["missing_cost_count"] == 1
    assert metrics["cost_complete"] is False


def test_compute_metrics_p95_uses_replied_pipeline_durations() -> None:
    """P95 取 replied 管线耗时的 ceil(0.95*n) 个序统计量。"""
    rows = [_row("replied", index * 100, 1) for index in range(1, 21)]
    metrics = compute_metrics(rows)
    assert metrics["p95_duration_ms"] == 1900
    assert metrics["avg_duration_ms"] == pytest.approx(1050)


def test_compute_metrics_empty_does_not_invent_p95_or_cost_sample() -> None:
    metrics = compute_metrics([])
    assert metrics["total"] == 0
    assert metrics["success_rate"] == 0.0
    assert metrics["p95_duration_ms"] is None
    assert metrics["total_cost_li"] == 0
    assert metrics["cost_complete"] is True
    assert compute_metrics([_row("replied", None)])["p95_duration_ms"] is None


def test_compute_metrics_does_not_mark_valid_skips_as_missing_cost() -> None:
    """未进入生成的静默分支没有 cost_li 是合法零生成样本。"""
    metrics = compute_metrics(
        [
            _row("skipped_decision", None),
            _row("rejected_moderation", None),
        ]
    )
    assert metrics["total_cost_li"] == 0
    assert metrics["missing_cost_count"] == 0
    assert metrics["cost_complete"] is True


async def test_fetch_rows_reads_legacy_schema_without_m5_columns(tmp_path) -> None:
    """日报读取 M4 旧库时把未存在的两列视为缺失样本，不报 SQL 错。"""
    db = tmp_path / "legacy.db"
    async with aiosqlite.connect(db) as connection:
        await connection.execute(
            "CREATE TABLE decisions ("
            "id INTEGER PRIMARY KEY AUTOINCREMENT,"
            "comment_id INTEGER NOT NULL,"
            "decision TEXT NOT NULL,"
            "mode TEXT,"
            "reason TEXT NOT NULL,"
            "created_at TEXT NOT NULL"
            ")"
        )
        await connection.execute(
            "INSERT INTO decisions (comment_id, decision, mode, reason, created_at) "
            "VALUES (1, 'replied', NULL, 'legacy', '2026-09-25T00:00:00+00:00')"
        )
        await connection.commit()
    rows = await fetch_rows(db, date(2026, 9, 25))
    assert rows == [{"decision": "replied", "duration_ms": None, "cost_li": None}]


def test_write_report_labels_pipeline_submission_metrics(tmp_path) -> None:
    metrics = compute_metrics([_row("replied", 120, 8)])
    json_path, markdown_path = write_report(metrics, tmp_path, date(2026, 9, 25))
    assert json_path.name == "metrics-2026-09-25.json"
    assert markdown_path.name == "metrics-2026-09-25.md"
    markdown = markdown_path.read_text(encoding="utf-8")
    assert "提交成功率" in markdown
    assert "管线耗时 P95" in markdown
    assert "真实审核通过成功率：待联调验证" in markdown
