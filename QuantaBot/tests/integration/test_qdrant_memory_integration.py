"""Qdrant 用户记忆真接 smoke（QUANTABOT_INTEGRATION=1 + compose qdrant；单测不连网）。"""

import pytest

from quanta_bot.infra.settings import Settings
from quanta_bot.memory.ports import MemoryOp
from quanta_bot.memory.user_memory import InMemoryUserMemoryStore  # noqa: F401 语义对照锚


@pytest.fixture
def qdrant_store():
    """真 Qdrant 记忆存储（compose qdrant + Qwen embedding 配置；collection 每模块唯一防串扰）。"""
    from qdrant_client import AsyncQdrantClient

    from quanta_bot.infra.embedding import QwenEmbeddingClient
    from quanta_bot.infra.qdrant_memory import QdrantUserMemoryStore

    settings = Settings()
    client = AsyncQdrantClient(url=settings.qdrant_url)
    embedding = QwenEmbeddingClient(
        settings.embedding_base_url,
        settings.embedding_api_key,
        settings.embedding_model,
        timeout_seconds=10.0,
    )
    store = QdrantUserMemoryStore(client, settings.qdrant_memory_collection, embedding)

    async def _factory():
        await store.ensure_collection(settings.embedding_dim)
        return store

    return _factory


async def test_qdrant_memory_full_lifecycle(qdrant_store) -> None:
    """ADD→recall 命中→UPDATE supersede→DELETE 软删（与 InMemory fake 同语义）。"""
    store = await qdrant_store()
    await store.apply_ops(
        42, "it1", [MemoryOp(op="ADD", type="user", content="跨专业考研", why="画像")]
    )
    hits = await store.recall(42, "it1", "考研准备", limit=5)
    assert any("跨专业考研" in r.content for r in hits)
    await store.apply_ops(
        42,
        "it1",
        [
            MemoryOp(
                op="UPDATE",
                type="user",
                content="考研目标院校已定",
                why="演进",
                target_memory_id=hits[0].memory_id,
            )
        ],
    )
    after_update = await store.recall(42, "it1", "考研", limit=5)
    assert all("跨专业考研" != r.content for r in after_update)  # 旧值 superseded 不再召回
    await store.apply_ops(
        42, "it1", [MemoryOp(op="DELETE", target_memory_id=after_update[0].memory_id)]
    )
    assert await store.recall(42, "it1", "考研", limit=5) == ()


async def test_qdrant_recall_isolation_by_user_and_version(qdrant_store) -> None:
    """隔离红线：不同 user / 不同 persona_version 互不可见（与 fake 同语义）。"""
    store = await qdrant_store()
    await store.apply_ops(
        42, "iso1", [MemoryOp(op="ADD", type="user", content="机械工程", why="画像")]
    )
    other_user = await store.recall(99, "iso1", "专业", limit=5)
    other_version = await store.recall(42, "iso2", "专业", limit=5)
    assert other_user == () and other_version == ()
    own = await store.recall(42, "iso1", "专业", limit=5)
    assert any("机械工程" in r.content for r in own)
