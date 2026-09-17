"""memory/ports 行为测试：fake 语义=粗召回过滤/负面全量/四态落库（superseded 留痕）。"""

import math
from datetime import UTC, datetime, timedelta

from quanta_bot.memory.ports import (
    HashEmbeddingClient,
    MemoryOp,
    MemoryRecord,
)
from quanta_bot.memory.user_memory import InMemoryUserMemoryStore


def _record(
    user_id: int = 42, content: str = "用户在准备跨专业考研", **kwargs: object
) -> MemoryRecord:
    """测试辅助：支持 type_/days_ago 便捷参数（Task 7 渲染用例复用本 helper）。"""
    base: dict[str, object] = {
        "user_id": user_id,
        "type": "user",
        "content": content,
        "why": "画像：影响答疑口径",
        "created_at": datetime.now(UTC),
        "persona_version": "abc123",
    }
    if "type_" in kwargs:
        kwargs["type"] = kwargs.pop("type_")
    if "days_ago" in kwargs:
        # days_ago 便捷参数 → 绝对日期（渲染衰减警告用例需要"X 天前"的可控时间锚点）
        kwargs["created_at"] = datetime.now(UTC) - timedelta(days=int(kwargs.pop("days_ago")))  # type: ignore[arg-type]
    base.update(kwargs)
    return MemoryRecord(**base)  # type: ignore[arg-type]


async def test_recall_filters_by_user_version_and_status() -> None:
    """粗召回只回 active + 同 persona_version + 同 user 的记忆（隔离红线）。"""
    store = InMemoryUserMemoryStore(HashEmbeddingClient(dim=64))
    await store.apply_ops(
        42, "abc123", [MemoryOp(op="ADD", type="user", content="跨专业考研", why="画像")]
    )
    await store.apply_ops(
        43, "abc123", [MemoryOp(op="ADD", type="user", content="另一用户", why="")]
    )
    await store.apply_ops(
        42, "xyz789", [MemoryOp(op="ADD", type="user", content="旧版本记忆", why="")]
    )
    hits = await store.recall(42, "abc123", "考研怎么准备", limit=5)
    assert len(hits) == 1 and hits[0].content == "跨专业考研"


async def test_update_supersedes_old_and_delete_is_soft() -> None:
    """UPDATE 旧值 superseded 留痕（不再召回）；DELETE 软删 status=deleted（可审计不物理删）。"""
    store = InMemoryUserMemoryStore(HashEmbeddingClient(dim=64))
    await store.apply_ops(
        42, "v1", [MemoryOp(op="ADD", type="project", content="在做毕业设计", why="动态")]
    )
    first = (await store.recall(42, "v1", "毕设", limit=5))[0]
    await store.apply_ops(
        42,
        "v1",
        [
            MemoryOp(
                op="UPDATE",
                type="project",
                content="毕设已进入答辩阶段",
                why="演进",
                target_memory_id=first.memory_id,
            ),
        ],
    )
    hits = await store.recall(42, "v1", "毕设", limit=5)
    assert len(hits) == 1 and hits[0].content == "毕设已进入答辩阶段"
    assert first.status == "superseded"  # 旧值留痕不召回
    await store.apply_ops(42, "v1", [MemoryOp(op="DELETE", target_memory_id=hits[0].memory_id)])
    assert (await store.recall(42, "v1", "毕设", limit=5)) == ()
    assert hits[0].status == "deleted"  # 软删可审计


async def test_negative_feedback_returns_all_active_negative() -> None:
    """负面偏好全量返回：2 条 negative 全在，positive 不混入。"""
    store = InMemoryUserMemoryStore(HashEmbeddingClient(dim=64))
    await store.apply_ops(
        42,
        "abc123",
        [
            MemoryOp(
                op="ADD",
                type="feedback",
                valence="negative",
                content="别再推考研班",
                why="用户明确反对",
            ),
            MemoryOp(
                op="ADD",
                type="feedback",
                valence="negative",
                content="不要提挂科的事",
                why="用户反感",
            ),
            MemoryOp(
                op="ADD", type="feedback", valence="positive", content="喜欢被叫学长", why="偏好"
            ),
        ],
    )
    negatives = await store.negative_feedback(42, "abc123")
    assert len(negatives) == 2  # 负面全量（不做 top-k 截断）
    assert all(record.valence == "negative" for record in negatives)
    assert all(record.content != "喜欢被叫学长" for record in negatives)  # positive 不混入


async def test_hash_embedding_deterministic() -> None:
    """假向量确定性：同文本同向量、dim 匹配、L2 归一化（‖v‖≈1）。"""
    client = HashEmbeddingClient(dim=64)
    first = (await client.embed(["跨专业考研"]))[0]
    second = (await client.embed(["跨专业考研"]))[0]
    assert first == second  # 同文本 → 同向量（hermetic 预置记忆的前提）
    assert len(first) == 64  # dim 匹配
    norm = math.sqrt(sum(component * component for component in first))
    assert math.isclose(norm, 1.0)  # 归一化 ‖v‖≈1
