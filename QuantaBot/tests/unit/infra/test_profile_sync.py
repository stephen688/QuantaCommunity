"""显式偏好同步 worker 行为测试：只处理 pending、HTTP 失败保留待办。"""

import httpx

from quanta_bot.infra.profile_sync import ProfileSyncWorker
from quanta_bot.memory.ports import PendingProfileSync


class _PendingStore:
    def __init__(self, pending: list[PendingProfileSync]) -> None:
        self.pending = pending
        self.cleared: list[str] = []

    async def list_pending_profile_sync(self, limit: int) -> tuple[PendingProfileSync, ...]:
        return tuple(self.pending[:limit])

    async def resolve_profile_sync_topics(
        self,
        event_id: str,
        memory_id: str,
        revision: int,
        topics: tuple[str, ...],
        valence: str | None = None,
    ) -> bool:
        for index, item in enumerate(self.pending):
            if (
                item.event_id == event_id
                and item.memory_id == memory_id
                and item.revision == revision
            ):
                self.pending[index] = item.model_copy(
                    update={"topics": topics, "valence": valence, "resolved": True}
                )
                return True
        return False

    async def clear_profile_sync(self, event_id: str, memory_id: str, revision: int) -> bool:
        for index, item in enumerate(self.pending):
            if (
                item.event_id == event_id
                and item.memory_id == memory_id
                and item.revision == revision
            ):
                self.pending.pop(index)
                self.cleared.append(event_id)
                return True
        return False


class _MainService:
    def __init__(self, *, fail_post: bool = False) -> None:
        self.fail_post = fail_post
        self.posts: list[tuple[str, dict[str, object]]] = []
        self.topic_requests = 0

    async def get_json(self, path: str, params=None) -> object:
        assert path == "/bot/profile/topics"
        self.topic_requests += 1
        return [
            {"id": "basketball", "label": "篮球", "aliases": ["篮球"]},
            {"id": "software_technology", "label": "软件技术", "aliases": ["编程"]},
        ]

    async def post_json(self, path: str, payload: dict[str, object]) -> object:
        assert path == "/bot/profile/events"
        if self.fail_post:
            raise httpx.ConnectError("main service unavailable")
        self.posts.append((path, payload))
        return None


def _pending(*, operation: str = "UPSERT", content: str = "我喜欢篮球") -> PendingProfileSync:
    return PendingProfileSync(
        event_id="evt-1",
        user_id=42,
        memory_id="memory-1",
        persona_version="pv1",
        revision=1_790_550_000_000_000,
        operation=operation,
        topics=(),
        valence="positive" if operation == "UPSERT" else None,
        content=content,
        resolved=operation == "DELETE",
    )


def _pending_without_valence() -> PendingProfileSync:
    return PendingProfileSync(
        event_id="evt-none",
        user_id=42,
        memory_id="memory-none",
        persona_version="pv1",
        revision=1_790_550_000_000_001,
        operation="UPSERT",
        topics=(),
        valence=None,
        content="我喜欢篮球",
    )


async def test_worker_posts_only_controlled_topics_and_clears_matching_pending() -> None:
    """成功响应后才清除同 revision pending，且请求不包含记忆原文。"""
    store = _PendingStore([_pending()])
    main_service = _MainService()
    worker = ProfileSyncWorker(store, main_service, batch_size=10)

    await worker.run_once()

    assert main_service.posts == [
        (
            "/bot/profile/events",
            {
                "eventId": "evt-1",
                "userId": 42,
                "memoryId": "memory-1",
                "personaVersion": "pv1",
                "revision": 1_790_550_000_000_000,
                "operation": "UPSERT",
                "topics": ["basketball"],
                "valence": "positive",
            },
        )
    ]
    assert "我喜欢篮球" not in str(main_service.posts)
    assert store.cleared == ["evt-1"]


async def test_worker_keeps_pending_when_http_fails() -> None:
    """主服务不可达不能伪装成功或丢弃待同步事件。"""
    store = _PendingStore([_pending()])
    worker = ProfileSyncWorker(store, _MainService(fail_post=True), batch_size=10)

    await worker.run_once()

    assert len(store.pending) == 1
    assert store.cleared == []


async def test_worker_clears_unmatched_upsert_without_http() -> None:
    """明确没有受控主题时只标记已处理，不让合法空结果无限重试。"""
    store = _PendingStore([_pending(content="我在计算机学院读书")])
    main_service = _MainService()
    worker = ProfileSyncWorker(store, main_service, batch_size=10)

    await worker.run_once()

    assert main_service.posts == []
    assert store.pending == []


async def test_worker_persists_inferred_positive_valence_before_post() -> None:
    store = _PendingStore([_pending_without_valence()])
    main_service = _MainService()
    worker = ProfileSyncWorker(store, main_service, batch_size=10)

    await worker.run_once()

    assert main_service.posts[0][1]["valence"] == "positive"
    assert store.pending == []
