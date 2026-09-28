"""显式画像同步事件契约测试。"""

import pytest
from pydantic import ValidationError

from quanta_bot.memory.ports import ProfileSyncEvent


def test_profile_sync_delete_carries_no_topics_or_valence() -> None:
    """DELETE 是撤销快照，不允许把旧主题增量重复发给主服务。"""
    event = ProfileSyncEvent(
        event_id="evt-delete",
        user_id=42,
        memory_id="memory-1",
        persona_version="pv1",
        revision=1_790_550_000_000_000,
        operation="DELETE",
        topics=(),
        valence=None,
    )
    assert event.model_dump() == {
        "event_id": "evt-delete",
        "user_id": 42,
        "memory_id": "memory-1",
        "persona_version": "pv1",
        "revision": 1_790_550_000_000_000,
        "operation": "DELETE",
        "topics": (),
        "valence": None,
    }


def test_profile_sync_upsert_rejects_more_than_three_topics() -> None:
    """同步边界与主服务最多三个主题的契约保持一致。"""
    with pytest.raises(ValidationError):
        ProfileSyncEvent(
            event_id="evt-upsert",
            user_id=42,
            memory_id="memory-1",
            persona_version="pv1",
            revision=1,
            operation="UPSERT",
            topics=("a", "b", "c", "d"),
            valence="positive",
        )
