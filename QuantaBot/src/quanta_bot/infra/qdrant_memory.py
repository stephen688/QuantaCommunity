"""infra/qdrant_memory —— UserMemoryStore 端口的 Qdrant 真实现。

职责：用户级记忆持久化（filter user_id+persona_version+status=active 粗召回 top-k；
      负面 feedback 全量 scroll；四态 apply——UPDATE 旧点 set_payload superseded + 新点 upsert，
      DELETE 软删 set_payload deleted，物理删除永远不做——审计红线）。
边界：不做 embedding（EmbeddingClient 注入）；不做熔断（M5）；
      collection 幂等创建（ensure_collection，dim 由调用方从 Settings 传）。
"""

import asyncio
import time
from collections.abc import Mapping, Sequence
from contextlib import AsyncExitStack
from datetime import UTC, datetime
from uuid import NAMESPACE_URL, uuid4, uuid5

from qdrant_client import AsyncQdrantClient
from qdrant_client.models import (
    FieldCondition,
    Filter,
    MatchValue,
    PointsList,
    PointStruct,
    SetPayload,
    SetPayloadOperation,
    UpsertOperation,
    WriteOrdering,
)

from quanta_bot.memory.ports import (
    EmbeddingClient,
    MemoryOp,
    MemoryRecord,
    MemoryValence,
    PendingProfileSync,
)

_PROFILE_SYNC_KEY = "profile_sync"
_PROFILE_SYNC_PENDING = "pending"
_PROFILE_SYNC_TYPES = {"user", "feedback"}


def _to_payload(
    record: MemoryRecord, profile_sync: dict[str, object] | None = None
) -> dict[str, object]:
    payload: dict[str, object] = {
        "user_id": record.user_id,
        "type": record.type,
        "valence": record.valence,
        "content": record.content,
        "why": record.why,
        "created_at": record.created_at.isoformat(),
        "persona_version": record.persona_version,
        "status": record.status,
    }
    if profile_sync is not None:
        payload[_PROFILE_SYNC_KEY] = profile_sync
    return payload


def _to_record(point_id: str, payload: dict[str, object]) -> MemoryRecord:
    return MemoryRecord(
        memory_id=str(point_id),
        user_id=int(payload["user_id"]),
        type=payload["type"],  # type: ignore[arg-type]
        valence=payload.get("valence"),
        content=str(payload["content"]),
        why=str(payload.get("why", "")),
        created_at=datetime.fromisoformat(str(payload["created_at"])),
        persona_version=str(payload["persona_version"]),
        status=payload.get("status", "active"),  # type: ignore[arg-type]
    )


class QdrantUserMemoryStore:
    """Qdrant 真实现（与 InMemory fake 同语义——单测/eval 用 fake，integration 用本类验证）。"""

    def __init__(
        self, client: AsyncQdrantClient, collection: str, embedding: EmbeddingClient
    ) -> None:
        self._client = client
        self._collection = collection
        self._embedding = embedding
        self._profile_locks: dict[str, asyncio.Lock] = {}

    async def ensure_collection(self, dim: int) -> None:
        if not await self._client.collection_exists(self._collection):
            from qdrant_client.models import Distance, VectorParams

            await self._client.create_collection(
                collection_name=self._collection,
                vectors_config=VectorParams(size=dim, distance=Distance.COSINE),
            )

    def _visibility_filter(self, user_id: int, persona_version: str) -> Filter:
        # 召回可见域：同 user + 同版本 + active（隔离红线，Qdrant filter pushdown）
        return Filter(
            must=[
                FieldCondition(key="user_id", match=MatchValue(value=user_id)),
                FieldCondition(key="persona_version", match=MatchValue(value=persona_version)),
                FieldCondition(key="status", match=MatchValue(value="active")),
            ]
        )

    async def recall(
        self, user_id: int, persona_version: str, query_text: str, limit: int
    ) -> tuple[MemoryRecord, ...]:
        (query_vec,) = await self._embedding.embed([query_text])
        result = await self._client.query_points(
            collection_name=self._collection,
            query=query_vec,
            query_filter=self._visibility_filter(user_id, persona_version),
            limit=limit,
            with_payload=True,
        )
        return tuple(_to_record(str(p.id), dict(p.payload or {})) for p in result.points)

    async def negative_feedback(
        self, user_id: int, persona_version: str
    ) -> tuple[MemoryRecord, ...]:
        # 负面偏好全量 scroll（永不参与精选截断——漏黑名单是安全问题）；filter 在 status=active
        # 基础上追加 type+valence 条件
        flt = self._visibility_filter(user_id, persona_version)
        flt.must.append(  # type: ignore[union-attr]
            FieldCondition(key="type", match=MatchValue(value="feedback"))
        )
        flt.must.append(  # type: ignore[union-attr]
            FieldCondition(key="valence", match=MatchValue(value="negative"))
        )
        records: list[MemoryRecord] = []
        offset = None
        while True:
            points, offset = await self._client.scroll(
                collection_name=self._collection,
                scroll_filter=flt,
                limit=100,
                offset=offset,
                with_payload=True,
            )
            records.extend(_to_record(str(p.id), dict(p.payload or {})) for p in points)
            if offset is None:
                return tuple(records)

    async def apply_ops(
        self,
        user_id: int,
        persona_version: str,
        ops: Sequence[MemoryOp],
        source_event_id: str | None = None,
    ) -> None:
        # Outbox event id 使同一评论重试得到相同 point/event；无事件调用沿用随机
        # 记忆 ID，但同一次调用内共享一个 source key，便于 UPDATE/DELETE 配对。
        source_key = source_event_id or uuid4().hex
        for op_index, op in enumerate(ops):  # 四态逐条应用（同帖串行下无并发竞争）
            if op.op == "ADD" and op.content:
                record = self._new_record(
                    user_id,
                    persona_version,
                    op,
                    stable_key=f"{source_key}:{op_index}:ADD",
                )
                marker = await self._profile_marker(
                    record,
                    "UPSERT",
                    source_key,
                    f"{op_index}:ADD:{record.memory_id}:UPSERT",
                )
                await self._upsert_record(record, marker)
            elif op.op == "UPDATE" and op.content and op.target_memory_id:
                await self._update_record(
                    user_id,
                    persona_version,
                    op,
                    source_key,
                    op_index,
                )
            elif op.op == "DELETE" and op.target_memory_id:
                await self._mark_existing(
                    user_id,
                    persona_version,
                    op.target_memory_id,
                    status="deleted",
                    source_key=source_key,
                    operation_key=f"{op_index}:DELETE:{op.target_memory_id}:DELETE",
                )
            # NOOP：跳过（瞬时状态防御——不落任何痕迹）

    async def _upsert_record(
        self, record: MemoryRecord, profile_marker: dict[str, object] | None
    ) -> None:
        if profile_marker is not None and profile_marker.get("status") == "done":
            return
        (vec,) = await self._embedding.embed([record.content])
        async with self._point_lock(record.memory_id):
            current_payload = await self._payload_for_id(record.memory_id)
            current_marker = (current_payload or {}).get(_PROFILE_SYNC_KEY)
            if isinstance(current_marker, Mapping):
                expected_event = str((profile_marker or {}).get("event_id", ""))
                if (
                    current_marker.get("status") != _PROFILE_SYNC_PENDING
                    or str(current_marker.get("event_id", "")) != expected_event
                ):
                    # worker/更新在 embed 等待期间已写入另一 revision 或 done tombstone。
                    return
                if current_marker.get("resolved"):
                    return
                profile_marker = dict(current_marker)
            elif current_payload is not None and current_payload.get("status") != "active":
                return
            await self._client.upsert(
                collection_name=self._collection,
                points=[
                    PointStruct(
                        id=record.memory_id,
                        vector=vec,
                        payload=_to_payload(record, profile_marker),
                    )
                ],
            )

    async def _update_record(
        self,
        user_id: int,
        persona_version: str,
        op: MemoryOp,
        source_key: str,
        op_index: int,
    ) -> None:
        target_id = op.target_memory_id
        if not target_id:
            return
        record = self._new_record(
            user_id,
            persona_version,
            op,
            stable_key=f"{source_key}:{op_index}:UPDATE:{target_id}",
        )
        lock_ids = sorted({target_id, record.memory_id})
        async with AsyncExitStack() as lock_stack:
            for lock_id in lock_ids:
                await lock_stack.enter_async_context(self._point_lock(lock_id))
            old_payload = await self._find_owned_payload(user_id, persona_version, target_id)
            if old_payload is None:
                return
            existing_new_payload = await self._payload_for_id(record.memory_id)
            if existing_new_payload is not None:
                # 稳定新点已存在即说明该 UPDATE 的新点写入已发生；可能已经被
                # 后续 DELETE 标成 pending，重放 UPDATE 不得覆盖撤销事件。
                return
            delete_marker = await self._profile_marker(
                _record_from_payload(target_id, old_payload),
                "DELETE",
                source_key,
                f"{op_index}:UPDATE:{target_id}:DELETE",
                existing_payload=old_payload,
            )
            upsert_marker = await self._profile_marker(
                record,
                "UPSERT",
                source_key,
                f"{op_index}:UPDATE:{target_id}:UPSERT",
            )
            point = await self._point_for_record(record, upsert_marker)
            old_update = {"status": "superseded"}
            if delete_marker is not None:
                old_update[_PROFILE_SYNC_KEY] = delete_marker
            # batch_update_points 保证顺序写入 WAL；每个点仍带 pending 标记，因而
            # 进程重启或批写部分成功时不会把同步事件变成“静默丢失”。
            await self._client.batch_update_points(
                collection_name=self._collection,
                update_operations=[
                    SetPayloadOperation(
                        set_payload=SetPayload(payload=old_update, points=[target_id])
                    ),
                    UpsertOperation(upsert=PointsList(points=[point])),
                ],
                wait=True,
                ordering=WriteOrdering.STRONG,
            )

    async def _point_for_record(
        self, record: MemoryRecord, profile_marker: dict[str, object] | None
    ) -> PointStruct:
        (vec,) = await self._embedding.embed([record.content])
        return PointStruct(
            id=record.memory_id,
            vector=vec,
            payload=_to_payload(record, profile_marker),
        )

    async def _mark_existing(
        self,
        user_id: int,
        persona_version: str,
        target_id: str,
        status: str,
        source_key: str,
        operation_key: str,
    ) -> None:
        # 归属校验（review M-8 收口）：目标点必须在本用户+本版本名下（不限状态），否则拒绝
        # （Qdrant set_payload 按 id 直改，先查归属防 LLM 幻觉 target 跨用户改写）
        async with self._point_lock(target_id):
            old_payload = await self._find_owned_payload(user_id, persona_version, target_id)
            if old_payload is None:
                return  # 跨用户/跨版本目标：拒绝（静默——LLM 判定错误不落库即可）
            expected_event = self._profile_event_id(source_key, operation_key)
            if _is_done_profile_marker(old_payload, expected_event, "DELETE"):
                return
            old_record = _record_from_payload(target_id, old_payload)
            marker = await self._profile_marker(
                old_record,
                "DELETE",
                source_key,
                operation_key,
                existing_payload=old_payload,
            )
            payload: dict[str, object] = {"status": status}
            if marker is not None:
                payload[_PROFILE_SYNC_KEY] = marker
            await self._client.set_payload(
                collection_name=self._collection,
                payload=payload,
                points=[target_id],
            )

    async def _find_owned_payload(
        self, user_id: int, persona_version: str, target_id: str
    ) -> dict[str, object] | None:
        offset = None
        while True:
            points, offset = await self._client.scroll(
                collection_name=self._collection,
                scroll_filter=Filter(
                    must=[
                        FieldCondition(key="user_id", match=MatchValue(value=user_id)),
                        FieldCondition(
                            key="persona_version", match=MatchValue(value=persona_version)
                        ),
                    ]
                ),
                limit=256,
                offset=offset,
                with_payload=True,
            )
            for point in points:
                if str(point.id) == target_id:
                    return dict(point.payload or {})
            if offset is None:
                return None

    async def _profile_marker(
        self,
        record: MemoryRecord,
        operation: str,
        source_key: str,
        operation_key: str,
        *,
        existing_payload: Mapping[str, object] | None = None,
    ) -> dict[str, object] | None:
        if record.type not in _PROFILE_SYNC_TYPES:
            return None
        event_id = self._profile_event_id(source_key, operation_key)
        if existing_payload is None:
            existing_payload = await self._payload_for_id(record.memory_id)
        old_marker = (existing_payload or {}).get(_PROFILE_SYNC_KEY)
        old_is_same_event = False
        if isinstance(old_marker, Mapping):
            same_event = (
                str(old_marker.get("event_id", "")) == event_id
                and str(old_marker.get("operation", "")) == operation
            )
            old_is_same_event = same_event
            if (
                old_marker.get("status") == "done"
                and str(old_marker.get("source_event_id", "")) == source_key
            ):
                return dict(old_marker)
            if same_event:
                revision = int(old_marker.get("revision", 0) or 0)
            else:
                revision = 0
        else:
            revision = 0
        if revision <= 0:
            revision = time.time_ns() // 1_000
        marker = {
            "status": _PROFILE_SYNC_PENDING,
            "event_id": event_id,
            "source_event_id": source_key,
            "user_id": record.user_id,
            "memory_id": record.memory_id,
            "persona_version": record.persona_version,
            "revision": revision,
            "operation": operation,
            "topics": [],
            "valence": record.valence if operation == "UPSERT" else None,
            "resolved": operation == "DELETE",
        }
        if old_is_same_event and isinstance(old_marker, Mapping):
            # 进程重启/同事件重放不能清空已解析但尚未发送的快照。
            marker["topics"] = list(old_marker.get("topics", ()))
            marker["resolved"] = bool(old_marker.get("resolved", False))
            if old_marker.get("valence") in {"positive", "negative"}:
                marker["valence"] = old_marker["valence"]
        return marker

    @staticmethod
    def _profile_event_id(source_key: str, operation_key: str) -> str:
        return uuid5(NAMESPACE_URL, f"quanta-profile:{source_key}:{operation_key}").hex

    def _point_lock(self, memory_id: str) -> asyncio.Lock:
        return self._profile_locks.setdefault(memory_id, asyncio.Lock())

    async def list_pending_profile_sync(self, limit: int) -> tuple[PendingProfileSync, ...]:
        records: list[PendingProfileSync] = []
        offset = None
        while len(records) < max(1, limit):
            points, offset = await self._client.scroll(
                collection_name=self._collection,
                scroll_filter=Filter(
                    must=[
                        FieldCondition(
                            key=f"{_PROFILE_SYNC_KEY}.status",
                            match=MatchValue(value=_PROFILE_SYNC_PENDING),
                        )
                    ]
                ),
                limit=min(100, max(1, limit - len(records))),
                offset=offset,
                with_payload=True,
            )
            for point in points:
                payload = dict(point.payload or {})
                marker = payload.get(_PROFILE_SYNC_KEY)
                if not isinstance(marker, Mapping) or marker.get("status") != _PROFILE_SYNC_PENDING:
                    continue
                operation = str(marker.get("operation", ""))
                if operation not in {"UPSERT", "DELETE"}:
                    continue
                topics_value = marker.get("topics", ())
                topics = (
                    tuple(str(topic) for topic in topics_value)
                    if isinstance(topics_value, (list, tuple))
                    else ()
                )
                valence_value = marker.get("valence")
                valence = valence_value if valence_value in {"positive", "negative"} else None
                records.append(
                    PendingProfileSync(
                        event_id=str(marker.get("event_id", "")),
                        user_id=int(marker.get("user_id", payload.get("user_id", 0))),
                        memory_id=str(marker.get("memory_id", point.id)),
                        persona_version=str(
                            marker.get("persona_version", payload.get("persona_version", ""))
                        ),
                        revision=int(marker.get("revision", 0)),
                        operation=operation,
                        topics=topics,
                        valence=valence,
                        content=(str(payload.get("content", "")) if operation == "UPSERT" else ""),
                        resolved=bool(marker.get("resolved", False)),
                    )
                )
                if len(records) >= limit:
                    break
            if offset is None or len(records) >= limit:
                break
        return tuple(records)

    async def resolve_profile_sync_topics(
        self,
        event_id: str,
        memory_id: str,
        revision: int,
        topics: tuple[str, ...],
        valence: MemoryValence | None = None,
    ) -> bool:
        if len(topics) > 3 or len(set(topics)) != len(topics):
            return False
        async with self._point_lock(memory_id):
            marker = await self._matching_pending_marker(event_id, memory_id, revision)
            if marker is None or marker.get("operation") != "UPSERT":
                return False
            if valence is not None:
                marker["valence"] = valence
            marker["topics"] = list(topics)
            marker["resolved"] = True
            await self._client.set_payload(
                collection_name=self._collection,
                payload={_PROFILE_SYNC_KEY: marker},
                points=[memory_id],
            )
            return True

    async def clear_profile_sync(self, event_id: str, memory_id: str, revision: int) -> bool:
        async with self._point_lock(memory_id):
            marker = await self._matching_pending_marker(event_id, memory_id, revision)
            if marker is None:
                return False
            # 保留 done tombstone，防止同一 source event 重放时重新 upsert/激活
            # 旧点或生成新的 revision；done 不再被 pending filter 扫描。
            marker["status"] = "done"
            marker["resolved"] = True
            await self._client.set_payload(
                collection_name=self._collection,
                payload={_PROFILE_SYNC_KEY: marker},
                points=[memory_id],
            )
            return True

    async def _matching_pending_marker(
        self, event_id: str, memory_id: str, revision: int
    ) -> dict[str, object] | None:
        payload = await self._payload_for_id(memory_id)
        if payload is None:
            return None
        marker = payload.get(_PROFILE_SYNC_KEY)
        if not isinstance(marker, Mapping):
            return None
        if (
            marker.get("status") != _PROFILE_SYNC_PENDING
            or str(marker.get("event_id", "")) != event_id
            or int(marker.get("revision", 0) or 0) != revision
        ):
            return None
        return dict(marker)

    async def _payload_for_id(self, memory_id: str) -> dict[str, object] | None:
        points = await self._client.retrieve(
            collection_name=self._collection,
            ids=[memory_id],
            with_payload=True,
        )
        if not points:
            return None
        return dict(points[0].payload or {})

    @staticmethod
    def _new_record(
        user_id: int,
        persona_version: str,
        op: MemoryOp,
        stable_key: str | None = None,
    ) -> MemoryRecord:
        memory_id = (
            uuid5(
                NAMESPACE_URL,
                f"quanta-memory:{user_id}:{persona_version}:{stable_key}",
            ).hex
            if stable_key
            else uuid4().hex
        )
        return MemoryRecord(
            memory_id=memory_id,
            user_id=user_id,
            type=op.type or "user",
            valence=op.valence,
            content=op.content or "",
            why=op.why or "",
            created_at=datetime.now(UTC),  # UTC 绝对时间（渲染衰减警告的时间锚点，与 fake 同口径）
            persona_version=persona_version,
        )


def _record_from_payload(point_id: str, payload: Mapping[str, object]) -> MemoryRecord:
    return _to_record(point_id, dict(payload))


def _is_done_profile_marker(
    payload: Mapping[str, object] | None,
    event_id: str,
    operation: str,
) -> bool:
    if payload is None:
        return False
    marker = payload.get(_PROFILE_SYNC_KEY)
    return (
        isinstance(marker, Mapping)
        and marker.get("status") == "done"
        and str(marker.get("event_id", "")) == event_id
        and str(marker.get("operation", "")) == operation
    )


def _is_done_profile_source(payload: Mapping[str, object] | None, source_event_id: str) -> bool:
    if payload is None:
        return False
    marker = payload.get(_PROFILE_SYNC_KEY)
    return (
        isinstance(marker, Mapping)
        and marker.get("status") == "done"
        and str(marker.get("source_event_id", "")) == source_event_id
    )
