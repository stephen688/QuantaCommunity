"""决策日志 SQLite 落盘的行为测试（真库，tmp_path 隔离）。"""

from quanta_bot.crosscutting.ports import DecisionLogEntry
from quanta_bot.infra.audit_db import SQLiteAudit


async def test_record_and_fetch_roundtrip(tmp_path) -> None:
    """record 落盘后可按时间序查回，字段完整不丢。"""
    db = str(tmp_path / "decisions.db")
    audit = SQLiteAudit(db)
    await audit.record(
        DecisionLogEntry(comment_id=7, decision="replied", mode="生活玩梗", reason="链路完整")
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


async def test_auto_creates_parent_dir(tmp_path) -> None:
    """父目录不存在时自动创建（data/ 首次运行）。"""
    audit = SQLiteAudit(str(tmp_path / "sub" / "dir" / "d.db"))
    await audit.record(DecisionLogEntry(comment_id=1, decision="failed", mode=None, reason="x"))
    assert await audit.fetch_entries()
