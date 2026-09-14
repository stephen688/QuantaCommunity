"""infra/audit_db —— DecisionAudit 端口的 SQLite 实现（真落盘，aiosqlite）。

职责：decisions 明细表的建表与写入；Langfuse 断连时近端兜底数据源（AGENTS.md Don't：不双写主服务 MySQL）。
边界：不做 Langfuse 上报（tracing.py M2）；连接策略为"每次写入短连接"（M1 触发量级下最稳，
      无连接生命周期管理坑；M5 若成为瓶颈再改为 composition 管理的常驻连接，属性能优化非行为变更）。
"""

from pathlib import Path
from typing import NamedTuple

import aiosqlite

from quanta_bot.crosscutting.ports import DecisionLogEntry

# 与 DecisionLogEntry 字段一一对应（created_at 存 ISO8601 文本）
_SCHEMA = """
CREATE TABLE IF NOT EXISTS decisions (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    comment_id INTEGER NOT NULL,
    decision TEXT NOT NULL,
    mode TEXT,
    reason TEXT NOT NULL,
    created_at TEXT NOT NULL
)
"""

# 查询行（fetch_entries 返回形态；行序=写入序）
_Row = NamedTuple("_Row", comment_id=int, decision=str, mode=str | None, reason=str, created_at=str)


class SQLiteAudit:
    """决策明细落盘（实现 DecisionAudit 端口；fetch_entries 供测试与自查）。"""

    def __init__(self, db_path: str) -> None:
        self._db_path = db_path

    async def record(self, entry: DecisionLogEntry) -> None:
        """落一条决策明细（自动建父目录与表）。"""
        Path(self._db_path).parent.mkdir(parents=True, exist_ok=True)
        async with aiosqlite.connect(self._db_path) as db:
            await db.execute(_SCHEMA)
            await db.execute(
                "INSERT INTO decisions (comment_id, decision, mode, reason, created_at) "
                "VALUES (?, ?, ?, ?, ?)",
                (
                    entry.comment_id,
                    entry.decision,
                    entry.mode,
                    entry.reason,
                    entry.created_at.isoformat(),
                ),
            )
            await db.commit()

    async def fetch_entries(self) -> list[_Row]:
        """按写入序查回全部决策明细（测试/自查用，不在端口契约上）。"""
        async with aiosqlite.connect(self._db_path) as db:
            await db.execute(_SCHEMA)
            cursor = await db.execute(
                "SELECT comment_id, decision, mode, reason, created_at FROM decisions ORDER BY id"
            )
            rows = await cursor.fetchall()
        return [_Row(*r) for r in rows]
