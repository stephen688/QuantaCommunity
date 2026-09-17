"""infra/qdrant_content —— RAG 内容索引（Qdrant qb_content collection；doc_kind payload 过滤检索）。

职责：社区内容/政策文档的向量化持久化（POLICY/POST/ANSWER docKind payload）+ Retriever 端口实现
      （query_points + doc_kind 可选 filter → RetrievedFragment）。
边界：不做 embedding（EmbeddingClient 注入，与记忆存储共用同一客户端——1024 维同源）；
      collection 幂等创建（ensure_collection，dim 由调用方从 Settings 传）；
      不做熔断（M5）；检索失败由管线 ⑧ 步降级兜底（不阻断回复）。
"""

from collections.abc import Sequence
from typing import Literal
from uuid import NAMESPACE_URL, uuid5

from qdrant_client import AsyncQdrantClient
from qdrant_client.models import FieldCondition, Filter, MatchValue, PointStruct

from quanta_bot.infra.content_sync import SyncDoc
from quanta_bot.memory.ports import EmbeddingClient
from quanta_bot.pipeline.ports import RetrievedFragment

DocKind = Literal["POLICY", "POST", "ANSWER"]


def _point_id(doc: SyncDoc) -> str:
    """把内容源 ID 映射为跨重启稳定的 UUID（Qdrant 不接受任意字符串 point ID）。"""
    return str(uuid5(NAMESPACE_URL, f"quantabot:content:{doc.doc_kind}:{doc.doc_id}"))


class QdrantContentIndex:
    """RAG 内容索引真实现（bot 自建 Qdrant——C-3 改形契约；demo0 的 /rag/search 留给社区搜索）。"""

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

    async def upsert_docs(self, docs: Sequence[SyncDoc]) -> int:
        if not docs:
            return 0
        # 向量化文本=title + content 前 500 字（标题定主题、正文定细节——检索两侧同构）
        texts = [f"{doc.title}\n{doc.content[:500]}" for doc in docs]
        vectors = await self._embedding.embed(texts)
        points = [
            PointStruct(
                id=_point_id(doc),
                vector=vec,
                payload={
                    "doc_kind": doc.doc_kind,
                    "title": doc.title,
                    "content": doc.content,
                    "source": doc.title or doc.doc_id,  # 无标题 canonical 文档仍保留稳定来源标识
                    "updated_at": doc.updated_at,
                },
            )
            for doc, vec in zip(docs, vectors, strict=True)
        ]
        await self._client.upsert(collection_name=self._collection, points=points)
        return len(points)

    async def retrieve(
        self,
        query: str,
        limit: int = 3,
        doc_kind: DocKind | None = None,
    ) -> tuple[RetrievedFragment, ...]:
        query_filter = None
        if doc_kind is not None:
            query_filter = Filter(
                must=[FieldCondition(key="doc_kind", match=MatchValue(value=doc_kind))]
            )
        (query_vec,) = await self._embedding.embed([query])
        result = await self._client.query_points(
            collection_name=self._collection,
            query=query_vec,
            query_filter=query_filter,
            limit=limit,
            with_payload=True,
        )
        return tuple(
            RetrievedFragment(
                content=str(p.payload.get("content", "")),
                source=str(p.payload.get("source", "")),
                score=float(p.score or 0.0),
                doc_kind=p.payload.get("doc_kind", "POST"),  # type: ignore[arg-type]
            )
            for p in result.points
        )
