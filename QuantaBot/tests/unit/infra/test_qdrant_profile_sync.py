"""Qdrant profile-sync payload durability and stable retry contract."""

from types import SimpleNamespace

import pytest
from qdrant_client import AsyncQdrantClient

from quanta_bot.infra.profile_sync import ProfileSyncWorker
from quanta_bot.infra.qdrant_memory import QdrantUserMemoryStore
from quanta_bot.memory.ports import HashEmbeddingClient, MemoryOp


class _FakeQdrant:
    def __init__(self) -> None:
        self.points: dict[str, dict[str, object]] = {}
        self.batch_calls: list[object] = []

    async def upsert(self, *, collection_name, points, **_kwargs):
        for point in points:
            self.points[str(point.id)] = dict(point.payload or {})

    async def retrieve(self, *, collection_name, ids, **_kwargs):
        return [
            SimpleNamespace(id=point_id, payload=self.points[str(point_id)])
            for point_id in ids
            if str(point_id) in self.points
        ]

    async def scroll(self, *, collection_name, **_kwargs):
        return (
            [
                SimpleNamespace(id=point_id, payload=payload)
                for point_id, payload in self.points.items()
            ],
            None,
        )

    async def set_payload(self, *, collection_name, payload, points, **_kwargs):
        for point_id in points:
            self.points[str(point_id)].update(payload)

    async def delete_payload(self, *, collection_name, keys, points, **_kwargs):
        for point_id in points:
            for key in keys:
                self.points[str(point_id)].pop(key, None)

    async def batch_update_points(self, *, collection_name, update_operations, **_kwargs):
        self.batch_calls.append(update_operations)
        for operation in update_operations:
            if getattr(operation, "set_payload", None) is not None:
                update = operation.set_payload
                for point_id in update.points or []:
                    self.points[str(point_id)].update(update.payload)
            elif getattr(operation, "upsert", None) is not None:
                for point in operation.upsert.points:
                    self.points[str(point.id)] = dict(point.payload or {})


@pytest.fixture
def store_and_client() -> tuple[QdrantUserMemoryStore, _FakeQdrant]:
    client = _FakeQdrant()
    return QdrantUserMemoryStore(client, "memories", HashEmbeddingClient(dim=4)), client


@pytest.mark.asyncio
async def test_add_persists_stable_pending_marker_and_retry_does_not_duplicate(
    store_and_client,
) -> None:
    store, client = store_and_client
    op = MemoryOp(op="ADD", type="feedback", valence="positive", content="我喜欢篮球")

    await store.apply_ops(7, "v1", [op], source_event_id="comment-1")
    first_pending = (await store.list_pending_profile_sync(10))[0]
    await store.apply_ops(7, "v1", [op], source_event_id="comment-1")

    assert len(client.points) == 1
    pending = await store.list_pending_profile_sync(10)
    assert len(pending) == 1
    assert pending[0].event_id
    assert pending[0].revision > 0
    assert pending[0].event_id == first_pending.event_id
    assert pending[0].revision == first_pending.revision
    assert pending[0].operation == "UPSERT"
    assert pending[0].resolved is False


@pytest.mark.asyncio
async def test_pending_resolve_and_clear_require_matching_revision(store_and_client) -> None:
    store, _client = store_and_client
    await store.apply_ops(
        7,
        "v1",
        [MemoryOp(op="ADD", type="feedback", valence="positive", content="我喜欢篮球")],
        source_event_id="comment-2",
    )
    pending = (await store.list_pending_profile_sync(10))[0]

    assert not await store.clear_profile_sync(
        pending.event_id, pending.memory_id, pending.revision + 1
    )
    assert await store.resolve_profile_sync_topics(
        pending.event_id, pending.memory_id, pending.revision, ("basketball",)
    )
    resolved = (await store.list_pending_profile_sync(10))[0]
    assert resolved.resolved is True
    assert resolved.topics == ("basketball",)
    assert await store.clear_profile_sync(pending.event_id, pending.memory_id, pending.revision)
    assert await store.list_pending_profile_sync(10) == ()


@pytest.mark.asyncio
async def test_empty_resolution_is_persisted_then_done_marker_is_replay_safe(
    store_and_client,
) -> None:
    store, client = store_and_client
    op = MemoryOp(op="ADD", type="user", content="我在篮球社团")
    await store.apply_ops(7, "v1", [op], source_event_id="comment-empty")
    pending = (await store.list_pending_profile_sync(10))[0]

    assert await store.resolve_profile_sync_topics(
        pending.event_id, pending.memory_id, pending.revision, ()
    )
    assert await store.clear_profile_sync(pending.event_id, pending.memory_id, pending.revision)
    assert client.points[pending.memory_id]["profile_sync"]["status"] == "done"
    await store.apply_ops(7, "v1", [op], source_event_id="comment-empty")
    assert await store.list_pending_profile_sync(10) == ()
    assert client.points[pending.memory_id]["status"] == "active"


@pytest.mark.asyncio
async def test_same_source_replay_cannot_reactivate_a_completed_delete(store_and_client) -> None:
    store, client = store_and_client
    add = MemoryOp(op="ADD", type="feedback", valence="positive", content="我喜欢篮球")
    await store.apply_ops(7, "v1", [add], source_event_id="comment-tombstone")
    memory_id = next(iter(client.points))
    upsert = (await store.list_pending_profile_sync(10))[0]
    await store.clear_profile_sync(upsert.event_id, upsert.memory_id, upsert.revision)

    await store.apply_ops(
        7,
        "v1",
        [MemoryOp(op="DELETE", target_memory_id=memory_id)],
        source_event_id="comment-delete",
    )
    delete_pending = (await store.list_pending_profile_sync(10))[0]
    await store.clear_profile_sync(
        delete_pending.event_id, delete_pending.memory_id, delete_pending.revision
    )
    await store.apply_ops(7, "v1", [add], source_event_id="comment-tombstone")

    assert await store.list_pending_profile_sync(10) == ()
    assert client.points[memory_id]["status"] == "deleted"


@pytest.mark.asyncio
async def test_replayed_update_does_not_overwrite_later_delete_pending(store_and_client) -> None:
    store, client = store_and_client
    await store.apply_ops(
        7,
        "v1",
        [MemoryOp(op="ADD", type="feedback", valence="positive", content="我喜欢篮球")],
        source_event_id="update-base",
    )
    old_id = next(iter(client.points))
    update = MemoryOp(
        op="UPDATE",
        type="feedback",
        valence="negative",
        content="我不喜欢篮球",
        target_memory_id=old_id,
    )
    await store.apply_ops(7, "v1", [update], source_event_id="update-b")
    new_id = next(
        item.memory_id
        for item in await store.list_pending_profile_sync(10)
        if item.operation == "UPSERT"
    )
    await store.apply_ops(
        7,
        "v1",
        [MemoryOp(op="DELETE", target_memory_id=new_id)],
        source_event_id="delete-c",
    )
    before_replay = next(
        item for item in await store.list_pending_profile_sync(10) if item.memory_id == new_id
    )

    await store.apply_ops(7, "v1", [update], source_event_id="update-b")
    after_replay = next(
        item for item in await store.list_pending_profile_sync(10) if item.memory_id == new_id
    )
    assert after_replay.operation == "DELETE"
    assert after_replay.event_id == before_replay.event_id
    assert client.points[new_id]["status"] == "deleted"


@pytest.mark.asyncio
async def test_real_qdrant_memory_sdk_add_worker_update_delete_smoke() -> None:
    """用 Qdrant 本地内存实现核对 batch/update/retrieve/nested pending 契约。"""

    class _MainService:
        def __init__(self) -> None:
            self.posts: list[dict[str, object]] = []

        async def get_json(self, path: str, params=None) -> object:
            assert path == "/bot/profile/topics"
            return [{"id": "basketball", "label": "篮球", "aliases": []}]

        async def post_json(self, path: str, payload: dict[str, object]) -> object:
            assert path == "/bot/profile/events"
            self.posts.append(payload)
            return None

    client = AsyncQdrantClient(location=":memory:")
    try:
        store = QdrantUserMemoryStore(client, "memories", HashEmbeddingClient(dim=4))
        await store.ensure_collection(4)
        main_service = _MainService()
        worker = ProfileSyncWorker(store, main_service, batch_size=10)
        add = MemoryOp(op="ADD", type="feedback", valence="positive", content="我喜欢篮球")

        await store.apply_ops(7, "v1", [add], source_event_id="sdk-add")
        add_pending = (await store.list_pending_profile_sync(10))[0]
        await worker.run_once()
        assert await store.list_pending_profile_sync(10) == ()
        assert main_service.posts[0]["topics"] == ["basketball"]

        update = MemoryOp(
            op="UPDATE",
            type="feedback",
            valence="negative",
            content="我不喜欢篮球",
            target_memory_id=add_pending.memory_id,
        )
        await store.apply_ops(7, "v1", [update], source_event_id="sdk-update")
        update_pending = await store.list_pending_profile_sync(10)
        new_id = next(item.memory_id for item in update_pending if item.operation == "UPSERT")
        await worker.run_once()
        assert len(main_service.posts) == 3

        await store.apply_ops(
            7,
            "v1",
            [MemoryOp(op="DELETE", target_memory_id=new_id)],
            source_event_id="sdk-delete",
        )
        await worker.run_once()
        assert len(main_service.posts) == 4
        records = await client.retrieve(collection_name="memories", ids=[new_id], with_payload=True)
        assert records[0].payload["status"] == "deleted"
        assert records[0].payload["profile_sync"]["status"] == "done"
    finally:
        await client.close()


@pytest.mark.asyncio
async def test_update_keeps_old_delete_and_new_upsert_pending_in_order(store_and_client) -> None:
    store, client = store_and_client
    await store.apply_ops(
        7,
        "v1",
        [MemoryOp(op="ADD", type="feedback", valence="positive", content="我喜欢篮球")],
        source_event_id="comment-3",
    )
    old_id = next(iter(client.points))
    await store.apply_ops(
        7,
        "v1",
        [
            MemoryOp(
                op="UPDATE",
                type="feedback",
                valence="negative",
                content="我不喜欢篮球",
                target_memory_id=old_id,
            )
        ],
        source_event_id="comment-4",
    )

    assert client.batch_calls
    markers = [payload.get("profile_sync") for payload in client.points.values()]
    assert {marker["operation"] for marker in markers if marker} == {"DELETE", "UPSERT"}
