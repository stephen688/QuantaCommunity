"""infra/qdrant_memory —— UserMemoryStore 端口的 Qdrant 真实现。

职责：用户级记忆持久化（filter user_id+persona_version+status=active 粗召回 top-k；
      负面 feedback 全量 scroll；四态 apply——UPDATE 旧点 set_payload superseded + 新点 upsert，
      DELETE 软删 set_payload deleted，物理删除永远不做——审计红线）。
边界：不做 embedding（EmbeddingClient 注入）；不做熔断（M5）；
      collection 幂等创建（ensure_collection，dim 由调用方从 Settings 传）。
"""

from collections.abc import Sequence
from datetime import UTC, datetime

from qdrant_client import AsyncQdrantClient
from qdrant_client.models import FieldCondition, Filter, MatchValue, PointStruct

from quanta_bot.memory.ports import EmbeddingClient, MemoryOp, MemoryRecord


def _to_payload(record: MemoryRecord) -> dict[str, object]:
    return {
        "user_id": record.user_id,
        "type": record.type,
        "valence": record.valence,
        "content": record.content,
        "why": record.why,
        "created_at": record.created_at.isoformat(),
        "persona_version": record.persona_version,
        "status": record.status,
    }


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

    async def apply_ops(self, user_id: int, persona_version: str, ops: Sequence[MemoryOp]) -> None:
        for op in ops:  # 四态逐条应用（同帖串行下无并发竞争，与 fake 同语义）
            if op.op == "ADD" and op.content:
                record = self._new_record(user_id, persona_version, op)
                await self._upsert_record(record)
            elif op.op == "UPDATE" and op.content and op.target_memory_id:
                await self._supersede(user_id, persona_version, op.target_memory_id)
                record = self._new_record(user_id, persona_version, op)
                await self._upsert_record(record)
            elif op.op == "DELETE" and op.target_memory_id:
                await self._supersede(
                    user_id, persona_version, op.target_memory_id, status="deleted"
                )
            # NOOP：跳过（瞬时状态防御——不落任何痕迹）

    async def _upsert_record(self, record: MemoryRecord) -> None:
        (vec,) = await self._embedding.embed([record.content])
        await self._client.upsert(
            collection_name=self._collection,
            points=[PointStruct(id=record.memory_id, vector=vec, payload=_to_payload(record))],
        )

    async def _supersede(
        self, user_id: int, persona_version: str, target_id: str, status: str = "superseded"
    ) -> None:
        # 归属校验（review M-8 收口）：目标点必须在本用户+本版本名下（不限状态），否则拒绝
        # （Qdrant set_payload 按 id 直改，先查归属防 LLM 幻觉 target 跨用户改写）
        owner_ids: set[object] = set()
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
                with_payload=False,
            )
            owner_ids.update(p.id for p in points)
            if offset is None:
                break
        if target_id not in owner_ids:
            return  # 跨用户/跨版本目标：拒绝（静默——LLM 判定错误不落库即可）
        await self._client.set_payload(
            collection_name=self._collection,
            payload={"status": status},
            points=[target_id],
        )

    @staticmethod
    def _new_record(user_id: int, persona_version: str, op: MemoryOp) -> MemoryRecord:
        return MemoryRecord(
            user_id=user_id,
            type=op.type or "user",
            valence=op.valence,
            content=op.content or "",
            why=op.why or "",
            created_at=datetime.now(UTC),  # UTC 绝对时间（渲染衰减警告的时间锚点，与 fake 同口径）
            persona_version=persona_version,
        )
