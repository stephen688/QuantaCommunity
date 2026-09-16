"""memory/user_memory —— 用户级记忆域逻辑（渲染 + 内存 fake 存储）。

职责：InMemoryUserMemoryStore（recall/negative_feedback/apply_ops 四态语义）；
      render_memory_block（D 通道注入渲染：负面全量 + 分类型衰减警告——Task 7 消费）。
边界：向量真实现/持久化在 infra/qdrant_memory.py；四态判定（产生 ops）在 pipeline/decision.py。
"""

from collections.abc import Sequence
from datetime import UTC, datetime

from quanta_bot.memory.ports import (
    EmbeddingClient,
    MemoryOp,
    MemoryRecord,
)


class InMemoryUserMemoryStore:
    """内存 fake：全量过滤 + 假向量余弦 top-k（hermetic 单测 / eval 预置记忆）。"""

    def __init__(self, embedding: EmbeddingClient) -> None:
        self._embedding = embedding
        self._records: list[MemoryRecord] = []

    def _active(self, user_id: int, persona_version: str) -> list[MemoryRecord]:
        # 召回可见域：同 user + 同版本 + active（隔离红线）
        return [
            record
            for record in self._records
            if record.user_id == user_id
            and record.persona_version == persona_version
            and record.status == "active"
        ]

    async def recall(
        self, user_id: int, persona_version: str, query_text: str, limit: int
    ) -> tuple[MemoryRecord, ...]:
        candidates = self._active(user_id, persona_version)
        if not candidates:
            return ()
        # 查询与候选一次性批量转向量（首条是 query，其余按 candidates 顺序对应）
        query_vec, *doc_vecs = await self._embedding.embed(
            [query_text] + [record.content for record in candidates]
        )
        # 假向量已 L2 归一化，点积即余弦相似度；按相似度降序取 top-limit
        scored = sorted(
            zip(candidates, doc_vecs, strict=True),
            key=lambda pair: (
                -sum(
                    query_dim * doc_dim
                    for query_dim, doc_dim in zip(query_vec, pair[1], strict=True)
                )
            ),
        )
        return tuple(record for record, _ in scored[:limit])

    async def negative_feedback(
        self, user_id: int, persona_version: str
    ) -> tuple[MemoryRecord, ...]:
        # 负面偏好全量返回（永不参与精选截断——漏黑名单是安全问题）
        return tuple(
            record
            for record in self._active(user_id, persona_version)
            if record.type == "feedback" and record.valence == "negative"
        )

    async def apply_ops(self, user_id: int, persona_version: str, ops: Sequence[MemoryOp]) -> None:
        for op in ops:  # 四态逐条应用（同帖串行下无并发竞争）
            if op.op == "ADD" and op.content:
                self._records.append(self._new_record(user_id, persona_version, op))
            elif op.op == "UPDATE" and op.content and op.target_memory_id:
                for record in self._records:  # 旧值 superseded 留痕（接得住过去）
                    if record.memory_id == op.target_memory_id:
                        record.status = "superseded"
                self._records.append(self._new_record(user_id, persona_version, op))
            elif op.op == "DELETE" and op.target_memory_id:
                for record in self._records:  # 软删（可审计不物理删）
                    if record.memory_id == op.target_memory_id:
                        record.status = "deleted"
            # NOOP：跳过（瞬时状态防御——不落任何痕迹）

    @staticmethod
    def _new_record(user_id: int, persona_version: str, op: MemoryOp) -> MemoryRecord:
        return MemoryRecord(
            user_id=user_id,
            type=op.type or "user",
            valence=op.valence,
            content=op.content or "",
            why=op.why or "",
            created_at=datetime.now(UTC),  # UTC 绝对时间（渲染衰减警告的时间锚点）
            persona_version=persona_version,
        )
