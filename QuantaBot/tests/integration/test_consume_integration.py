"""本地 RabbitMQ 真消费集成：C-1 契约消息端到端 + 同帖串行时序（防穿越本地等价物）。

前置：compose 栈 rabbitmq 在跑（QUANTABOT_INTEGRATION=1 + .env 配 QUANTABOT_MQ_URL）。
拓扑（quantabot.exchange / quantabot.comment.queue / rk quantabot.comment.created）由本测试
自建（生产由 demo0 D4 声明）。
"""

import asyncio
import contextlib
import json

import aio_pika

from quanta_bot.consumer import QUANTABOT_QUEUE, CommentEventConsumer
from quanta_bot.crosscutting.killswitch import SWITCH_KILL_KEY, ControlPlane
from quanta_bot.infra.audit_db import SQLiteAudit
from quanta_bot.infra.deepseek import FakeLLM
from quanta_bot.infra.kv import InMemoryKV
from quanta_bot.infra.main_service import FakeCommentTreeFetcher, FakeReplyWriter
from quanta_bot.infra.settings import Settings
from quanta_bot.memory.ports import HashEmbeddingClient
from quanta_bot.memory.user_memory import InMemoryUserMemoryStore
from quanta_bot.pipeline.context import LLMSummarizer
from quanta_bot.pipeline.persona import PersonaLibrary
from quanta_bot.pipeline.pipeline import PipelineDeps
from quanta_bot.pipeline.ports import CommentNode, PostSummary, PostThread, RunTrace

_EXCHANGE = "quantabot.exchange"
_ROUTING_KEY = "quantabot.comment.created"

# M3 决策层剧本（replied 路径首位 LLM 响应必须是合法决策 JSON——与 unit 同口径）
_DECISION_JSON = (
    '{"should_reply": true, "mode": "生活玩梗", "confidence": 0.9, "reason": "真诚求助"}'
)


class SpyTracer:
    def __init__(self) -> None:
        self.traces: list[RunTrace] = []

    async def record(self, trace: RunTrace) -> None:
        self.traces.append(trace)


class _SnapshotFetcher:
    """楼层快照时序间谍：fetch_context 时记录当时已写库回复数（防穿越断言锚点）。

    真实链路中该数字来自 demo0 C-2③ history 接口（bot 历史发言）；本地以「串行时序」
    作为等价物——第二条消息组装上下文时第一条回复必须已写库。
    """

    def __init__(self, writer: FakeReplyWriter) -> None:
        self._writer = writer
        self.snapshots: list[int] = []

    async def fetch_context(self, event) -> PostThread:
        self.snapshots.append(len(self._writer.written))
        return PostThread(
            post=PostSummary(
                post_id=event.post_id, author_user_id=1, title="集成测试主楼", content="占位"
            )
        )

    async def fetch_floors(self, post_id: int) -> tuple[CommentNode, ...]:
        return ()  # 楼层恒空=远区摘要零调用（M3 ⑤ 步契约：拉取全量楼层）


def _mq_message(comment_id: int, post_id: int, content: str) -> bytes:
    return json.dumps(
        {
            "eventId": f"evt-int-{comment_id}",
            "eventType": "BOT_MENTION_REQUESTED",
            "occurredAt": "2026-09-15T10:00:00Z",
            "retryCount": 0,
            "commentId": comment_id,
            "postId": post_id,
            "answerId": None,
            "commenterUserId": 5,
            "commentContent": content,
            "commentImages": [],
            "mentionedBot": True,
            "botTriggerKind": "mentioned",
            "parentId": None,
            "replyCommentId": None,
        }
    ).encode()


async def _publish(s: Settings, messages: list[bytes]) -> None:
    connection = await aio_pika.connect(s.mq_url)
    async with connection:
        channel = await connection.channel()
        exchange = await channel.declare_exchange(
            _EXCHANGE, aio_pika.ExchangeType.DIRECT, durable=True
        )
        queue = await channel.declare_queue(QUANTABOT_QUEUE, durable=True)
        await queue.bind(exchange, routing_key=_ROUTING_KEY)
        for body in messages:
            await exchange.publish(
                aio_pika.Message(body=body, delivery_mode=aio_pika.DeliveryMode.PERSISTENT),
                routing_key=_ROUTING_KEY,
            )


async def _wait_until(predicate, timeout: float = 30.0) -> None:
    deadline = asyncio.get_event_loop().time() + timeout
    while asyncio.get_event_loop().time() < deadline:
        if predicate():
            return
        await asyncio.sleep(0.2)
    raise AssertionError("等待超时（30s）——消费者未处理完消息")


async def test_consume_same_post_messages_serialized(tmp_path) -> None:
    """验收 #3 本地等价：同帖两条 @ 契约消息 → 串行处理；第二条组装上下文时已见第一条回复。"""
    s = Settings()
    assert s.mq_url, "integration 需要 .env 配置 QUANTABOT_MQ_URL"
    audit = SQLiteAudit(str(tmp_path / "int.db"))
    writer = FakeReplyWriter()
    kv = InMemoryKV()
    fetcher = _SnapshotFetcher(writer)
    # 两条消息 ×（决策+生成）：M3 链路首次调用为决策 JSON，剧本按消费序弹出（幂等重发不调 LLM）
    llm = FakeLLM(responses=[_DECISION_JSON, "第一条集成回复", _DECISION_JSON, "第二条集成回复"])
    deps = PipelineDeps(
        kv=kv,
        audit=audit,
        reply_writer=writer,
        llm=llm,
        tracer=SpyTracer(),
        control_plane=ControlPlane(kv),
        comment_tree=fetcher,
        persona=PersonaLibrary(),  # M3 必填三件（人格/记忆/摘要）
        memory_store=InMemoryUserMemoryStore(HashEmbeddingClient()),
        summarizer=LLMSummarizer(llm),
    )
    consumer = CommentEventConsumer(s.mq_url, deps, ControlPlane(kv))
    task = asyncio.create_task(consumer.run_forever())
    try:
        await _publish(
            s,
            [
                _mq_message(201, 77, "@QuantaBot 帮我看看第一条选课问题"),
                _mq_message(202, 77, "@QuantaBot 再看看第二条保研政策"),
            ],
        )
        await _wait_until(lambda: len(writer.written) >= 2)
        assert len(writer.written) == 2
        # 防穿越时序断言：第二条 fetch_context 调用时，第一条回复已写库
        assert fetcher.snapshots == [0, 1]
        entries = await audit.fetch_entries()
        assert [e.decision for e in entries] == ["replied", "replied"]
        # 幂等（MQ 至少一次投递语义）：重发第一条 → 不重复写库 + 记 skipped_idempotent
        await _publish(s, [_mq_message(201, 77, "@QuantaBot 帮我看看第一条选课问题")])
        entries_after_dup: list[str] = []
        for _ in range(100):
            entries_after_dup = [e.decision for e in await audit.fetch_entries()]
            if "skipped_idempotent" in entries_after_dup:
                break
            await asyncio.sleep(0.2)
        assert "skipped_idempotent" in entries_after_dup
        assert len(writer.written) == 2
    finally:
        task.cancel()
        with contextlib.suppress(asyncio.CancelledError):
            await task


async def test_consume_paused_while_kill_enabled(tmp_path) -> None:
    """G5 消费暂停（可观察）：kill 置位 → 零新回复；解除 → 恢复处理。"""
    s = Settings()
    assert s.mq_url
    audit = SQLiteAudit(str(tmp_path / "int.db"))
    writer = FakeReplyWriter()
    kv = InMemoryKV()
    cp = ControlPlane(kv)
    # kill 解除后消费一条：决策+生成两响应（M3 首次调用为决策 JSON）
    llm = FakeLLM(responses=[_DECISION_JSON, "kill 恢复后回复"])
    deps = PipelineDeps(
        kv=kv,
        audit=audit,
        reply_writer=writer,
        llm=llm,
        tracer=SpyTracer(),
        control_plane=cp,
        comment_tree=FakeCommentTreeFetcher(),
        persona=PersonaLibrary(),  # M3 必填三件（人格/记忆/摘要）
        memory_store=InMemoryUserMemoryStore(HashEmbeddingClient()),
        summarizer=LLMSummarizer(llm),
    )
    consumer = CommentEventConsumer(s.mq_url, deps, cp, poll_seconds=0.05)
    await kv.set(SWITCH_KILL_KEY, "true", ttl_seconds=60)
    await cp.refresh()
    task = asyncio.create_task(consumer.run_forever())
    try:
        await _publish(s, [_mq_message(301, 88, "@QuantaBot kill 期间的消息")])
        await asyncio.sleep(2)
        assert writer.written == []  # kill 置位期间零新回复（消息持有未 ack）
        await kv.set(SWITCH_KILL_KEY, "false", ttl_seconds=60)
        await cp.refresh()
        await _wait_until(lambda: len(writer.written) == 1)
    finally:
        task.cancel()
        with contextlib.suppress(asyncio.CancelledError):
            await task
