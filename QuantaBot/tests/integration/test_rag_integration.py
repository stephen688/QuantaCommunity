"""RAG/Redis 真接 smoke：摄取检索、对话 TTL 与远区摘要缓存复用。"""

from datetime import UTC, datetime
from uuid import uuid4

import pytest
import redis.asyncio as aioredis

from quanta_bot.infra.content_sync import ingest_content
from quanta_bot.infra.deepseek import FakeLLM
from quanta_bot.infra.kv import RedisKV
from quanta_bot.infra.settings import Settings
from quanta_bot.memory.dialogue import DialogueTurn, append_turn, dialogue_key, read_chain
from quanta_bot.pipeline.context import LLMSummarizer, _summary_cache_key, build_channel_c
from quanta_bot.pipeline.ports import CommentNode


@pytest.fixture
async def redis_kv():
    """单文件专用真 Redis fixture；关闭连接，不清理共享实例或数据卷。"""
    settings = Settings()
    assert settings.redis_url, "integration 需要 .env 配置 QUANTABOT_REDIS_URL"
    kv = RedisKV(settings.redis_url, settings.redis_timeout_seconds)
    try:
        assert await kv.ping() is True
        yield kv
    finally:
        await kv.aclose()


async def test_rag_ingest_and_retrieve(rag_stack) -> None:
    """合成源摄取真 Qdrant 后，POLICY 命中且 payload doc_kind 过滤生效。"""
    count = await ingest_content(rag_stack.source, rag_stack.index)
    assert count >= 8

    fragments = await rag_stack.retriever.retrieve(
        "奖助学金什么时候评审", limit=3, doc_kind="POLICY"
    )
    assert fragments
    assert all(fragment.doc_kind == "POLICY" for fragment in fragments)
    assert "评审" in fragments[0].content

    post_fragments = await rag_stack.retriever.retrieve(
        "奖助学金什么时候评审", limit=3, doc_kind="POST"
    )
    assert post_fragments
    assert all(fragment.doc_kind == "POST" for fragment in post_fragments)


async def test_dialogue_and_summary_cache_on_redis(redis_kv) -> None:
    """对话链/摘要写真 Redis 并带 TTL；第二次摘要命中缓存，不新增 LLM 调用。"""
    settings = Settings()
    post_id = int(uuid4().hex[:12], 16)
    turn = DialogueTurn(
        turn_id=post_id + 1,
        reply_content="第一条真实 Redis 对话回复",
        created_at=datetime.now(UTC),
    )
    await append_turn(redis_kv, post_id, turn, ttl_hours=1)

    turns = await read_chain(redis_kv, post_id)
    assert turns == (turn,)

    remote = tuple(
        CommentNode(
            commentId=post_id + offset,
            parentId=None,
            userId=7,
            content=f"远区楼层{offset}讨论奖助学金评审",
        )
        for offset in range(10, 13)
    )
    llm = FakeLLM(
        responses=[
            '{"topic":"奖助学金","conclusions":[],"disputes":[],'
            '"unanswered_questions":["什么时候评审"],"key_facts":[]}'
        ]
    )
    summarizer = LLMSummarizer(llm)
    first, _, first_from_cache = await build_channel_c(
        remote,
        summarizer,
        redis_kv,
        post_id=post_id,
        persona_version="integration-v1",
        summary_cache_ttl_hours=1,
    )
    second, _, second_from_cache = await build_channel_c(
        remote,
        summarizer,
        redis_kv,
        post_id=post_id,
        persona_version="integration-v1",
        summary_cache_ttl_hours=1,
    )

    assert first == second
    assert first_from_cache is False and second_from_cache is True
    assert len(llm.calls) == 1

    dialogue_cache_key = dialogue_key(post_id)
    summary_cache_key = _summary_cache_key(post_id, remote, "integration-v1")
    assert dialogue_cache_key.startswith("quantabot:")
    assert summary_cache_key.startswith("quantabot:")
    redis_client = aioredis.from_url(settings.redis_url, decode_responses=True)
    try:
        dialogue_ttl = await redis_client.ttl(dialogue_cache_key)
        summary_ttl = await redis_client.ttl(summary_cache_key)
    finally:
        await redis_client.aclose()
    assert dialogue_ttl > 0 and summary_ttl > 0
