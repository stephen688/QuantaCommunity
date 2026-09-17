"""Qdrant 内容索引边界测试：真实 Qdrant 连接走 integration 档。"""

from types import SimpleNamespace
from uuid import UUID

from quanta_bot.infra.content_sync import SyncDoc
from quanta_bot.infra.qdrant_content import QdrantContentIndex


class _EmbeddingStub:
    async def embed(self, texts: list[str]) -> list[list[float]]:
        return [[float(index)] for index, _ in enumerate(texts, start=1)]


class _QdrantStub:
    def __init__(self) -> None:
        self.exists = False
        self.created = []
        self.upserted = []
        self.query_kwargs = None

    async def collection_exists(self, collection: str) -> bool:
        return self.exists

    async def create_collection(self, **kwargs) -> None:
        self.created.append(kwargs)
        self.exists = True

    async def upsert(self, **kwargs) -> None:
        self.upserted.extend(kwargs["points"])

    async def query_points(self, **kwargs):
        self.query_kwargs = kwargs
        return SimpleNamespace(
            points=[
                SimpleNamespace(
                    payload={
                        "content": "奖助学金每年评审",
                        "source": "政策库",
                        "doc_kind": "POLICY",
                    },
                    score=0.9,
                )
            ]
        )


async def test_qdrant_content_index_upserts_and_retrieves_filtered_docs() -> None:
    """内容写入保留 doc_kind/source，检索按 doc_kind 过滤并映射片段。"""
    client = _QdrantStub()
    index = QdrantContentIndex(client, "qb_content", _EmbeddingStub())  # type: ignore[arg-type]
    docs = [
        SyncDoc(
            doc_id="policy-1",
            doc_kind="POLICY",
            title="奖助学金",
            content="奖助学金每年评审",
            updated_at="2026-09-01",
        )
    ]

    assert await index.upsert_docs(docs) == 1
    fragments = await index.retrieve("奖助学金", limit=3, doc_kind="POLICY")

    assert client.upserted[0].payload["doc_kind"] == "POLICY"
    assert client.upserted[0].payload["content"] == "奖助学金每年评审"
    assert fragments[0].content == "奖助学金每年评审"
    assert fragments[0].source == "政策库"
    assert fragments[0].doc_kind == "POLICY"
    assert client.query_kwargs["limit"] == 3
    assert client.query_kwargs["query_filter"].must[0].key == "doc_kind"


async def test_qdrant_content_index_creates_collection_once() -> None:
    """内容 collection 不存在时创建，重复启动不重复创建。"""
    client = _QdrantStub()
    index = QdrantContentIndex(client, "qb_content", _EmbeddingStub())  # type: ignore[arg-type]

    await index.ensure_collection(1024)
    await index.ensure_collection(1024)

    assert len(client.created) == 1
    assert client.created[0]["collection_name"] == "qb_content"
    assert client.created[0]["vectors_config"].size == 1024


async def test_qdrant_content_index_uses_stable_valid_point_ids() -> None:
    """外部内容 ID 不是 Qdrant ID 形状时，写入仍使用稳定合法的 point ID。"""
    client = _QdrantStub()
    index = QdrantContentIndex(client, "qb_content", _EmbeddingStub())  # type: ignore[arg-type]
    doc = SyncDoc(
        doc_id="policy-1",
        doc_kind="POLICY",
        title="奖助学金",
        content="奖助学金每年评审",
        updated_at="2026-09-01",
    )

    await index.upsert_docs([doc])
    first_id = str(client.upserted[-1].id)
    await index.upsert_docs([doc])
    second_id = str(client.upserted[-1].id)

    UUID(first_id)
    assert first_id == second_id
