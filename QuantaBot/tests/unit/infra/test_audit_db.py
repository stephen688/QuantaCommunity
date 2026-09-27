"""决策日志 SQLite 落盘的行为测试（真库，tmp_path 隔离）。"""

import aiosqlite

from quanta_bot.crosscutting.ports import DecisionLogEntry
from quanta_bot.infra.audit_db import SQLiteAudit


async def test_record_and_fetch_roundtrip(tmp_path) -> None:
    """record 落盘后可按时间序查回，字段完整不丢。"""
    db = str(tmp_path / "decisions.db")
    audit = SQLiteAudit(db)
    await audit.record(
        DecisionLogEntry(
            comment_id=7,
            decision="replied",
            mode="生活玩梗",
            reason="链路完整",
            duration_ms=321,
            cost_li=8,
        )
    )
    await audit.record(
        DecisionLogEntry(
            comment_id=8, decision="rejected_moderation", mode=None, reason="命中敏感词"
        )
    )
    entries = await audit.fetch_entries()
    assert [e.comment_id for e in entries] == [7, 8]
    assert entries[0].decision == "replied"
    assert entries[1].mode is None
    assert entries[0].duration_ms == 321
    assert entries[0].cost_li == 8
    assert entries[1].duration_ms is None
    assert entries[1].cost_li is None


async def test_auto_creates_parent_dir(tmp_path) -> None:
    """父目录不存在时自动创建（data/ 首次运行）。"""
    audit = SQLiteAudit(str(tmp_path / "sub" / "dir" / "d.db"))
    await audit.record(DecisionLogEntry(comment_id=1, decision="failed", mode=None, reason="x"))
    assert await audit.fetch_entries()


async def test_legacy_schema_is_migrated_without_losing_rows(tmp_path) -> None:
    """旧版 decisions 表可读，并在下一次写入时安全补齐 M5 两列。"""
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

    audit = SQLiteAudit(str(db))
    legacy_entries = await audit.fetch_entries()
    assert len(legacy_entries) == 1
    assert legacy_entries[0].duration_ms is None
    assert legacy_entries[0].cost_li is None

    await audit.record(
        DecisionLogEntry(
            comment_id=2,
            decision="replied",
            reason="new",
            duration_ms=42,
            cost_li=3,
        )
    )
    entries = await audit.fetch_entries()
    assert [entry.comment_id for entry in entries] == [1, 2]
    assert entries[1].duration_ms == 42
    assert entries[1].cost_li == 3
