"""Qdrant 记忆 payload 映射测试（真连接行为走 integration 档）。"""

from datetime import UTC, datetime

from quanta_bot.infra.qdrant_memory import _to_payload, _to_record
from quanta_bot.memory.ports import MemoryRecord


def test_payload_roundtrip_preserves_contract() -> None:
    record = MemoryRecord(
        user_id=42,
        type="feedback",
        valence="negative",
        content="别推考研班",
        why="负面偏好",
        created_at=datetime(2026, 9, 1, tzinfo=UTC),
        persona_version="abc",
    )
    assert _to_record(record.memory_id, _to_payload(record)) == record  # 往返无损


def test_payload_roundtrip_defaults_survive() -> None:
    """缺省字段（valence=None/status 默认 active）往返后语义不变。"""
    record = MemoryRecord(
        user_id=7,
        type="user",
        valence=None,
        content="跨专业考研",
        why="画像",
        created_at=datetime(2026, 9, 1, tzinfo=UTC),
        persona_version="pv1",
    )
    restored = _to_record(record.memory_id, _to_payload(record))
    assert restored.valence is None
    assert restored.status == "active"
