"""memory/ports —— 记忆层数据契约与端口（协议零实现，infra 提供真实现）。

职责：用户级记忆的记录/操作/存储契约（强制 schema：type 四类 + why + 绝对日期 + persona_version + status，
蓝图 §2.2 写入纪律）；EmbeddingClient 端口（记忆与 RAG 共用）+ HashEmbeddingClient 确定性假向量。
边界：对话级记忆不在此（memory/dialogue.py，走 KeyValueStore 楼层链）；
      渲染逻辑（衰减警告/注入格式）在 memory/user_memory.py；
      Qdrant/HTTP 实现在 infra（禁止本层 import infra）。
"""

import hashlib
import math
from collections.abc import Sequence
from datetime import datetime
from typing import Literal, Protocol
from uuid import uuid4

from pydantic import BaseModel, Field

# 记忆四类型（蓝图 §2.2 强制 schema：user 画像 / feedback 偏好 / project 动态 / reference 指针）
MemoryType = Literal["user", "feedback", "project", "reference"]
# feedback 类专用极性：negative=反对/黑名单（召回时全量注入、永不参与精选截断——蓝图 §5.1）
MemoryValence = Literal["positive", "negative"]
# 记忆状态：active 可召回；superseded 被 UPDATE 留痕；deleted 软删可审计
MemoryStatus = Literal["active", "superseded", "deleted"]


class MemoryRecord(BaseModel):
    """一条用户级记忆（强制 schema——自由文本无约束的记忆库三个月后必然是垃圾堆）。"""

    memory_id: str = Field(default_factory=lambda: uuid4().hex)
    user_id: int
    type: MemoryType
    valence: MemoryValence | None = None
    content: str
    why: str = ""
    created_at: datetime  # 绝对日期（写入侧保证"这学期"→"2026 春季学期"）
    persona_version: str
    status: MemoryStatus = "active"


class MemoryOp(BaseModel):
    """四态操作（决策层一车四用输出；ADD/UPDATE 携带候选，UPDATE/DELETE 携带目标）。"""

    op: Literal["ADD", "UPDATE", "DELETE", "NOOP"]
    type: MemoryType | None = None
    valence: MemoryValence | None = None
    content: str | None = None
    why: str | None = None
    target_memory_id: str | None = None


class UserMemoryStore(Protocol):
    """用户级记忆存储端口（fake=InMemoryUserMemoryStore，真=infra Qdrant，端口不变）。

    行为契约：recall 只见同 user + 同 persona_version + active 的记忆（隔离红线）；
    negative_feedback 全量返回负面偏好（永不参与精选截断）；apply_ops 四态留痕语义。
    """

    async def recall(
        self, user_id: int, persona_version: str, query_text: str, limit: int
    ) -> tuple[MemoryRecord, ...]:
        """粗召回：可见域内记忆按与 query_text 的相关性取 top-limit。"""
        ...

    async def negative_feedback(
        self, user_id: int, persona_version: str
    ) -> tuple[MemoryRecord, ...]:
        """负面偏好全量返回（漏黑名单是安全问题，不是相关性问题——蓝图 §5.1）。"""
        ...

    async def apply_ops(self, user_id: int, persona_version: str, ops: Sequence[MemoryOp]) -> None:
        """应用四态操作：UPDATE 旧值 superseded 留痕、DELETE 软删、NOOP 不落任何痕迹。"""
        ...


class EmbeddingClient(Protocol):
    """向量端口（infra 提供 Qwen 真实现；本文件提供 Hash 假实现）。"""

    async def embed(self, texts: list[str]) -> list[list[float]]:
        """批量文本转向量（顺序对应返回）。"""
        ...


class HashEmbeddingClient:
    """确定性假向量：sha256(text) 循环取 dim 字节再归一化——单测/eval 预置记忆 hermetic 用。"""

    def __init__(self, dim: int = 1024) -> None:
        self._dim = dim

    async def embed(self, texts: list[str]) -> list[list[float]]:
        vectors = []
        for text in texts:
            digest = hashlib.sha256(text.encode("utf-8")).digest()
            # sha256 仅 32 字节，循环取模铺满 dim 维（确定性：同文本必得同向量）
            raw = [float(digest[i % len(digest)]) for i in range(self._dim)]
            norm = math.sqrt(sum(component * component for component in raw)) or 1.0
            vectors.append([component / norm for component in raw])  # L2 归一化（点积即余弦）
        return vectors
