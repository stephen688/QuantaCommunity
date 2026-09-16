"""consumer 行为测试（Stub 消息直测 _handle——契约校验/兜底/kill 暂停/ack 语义）。"""

import asyncio
import json

from quanta_bot.consumer import CommentEventConsumer
from quanta_bot.crosscutting.killswitch import SWITCH_KILL_KEY, ControlPlane
from quanta_bot.infra.audit_db import SQLiteAudit
from quanta_bot.infra.deepseek import FakeLLM
from quanta_bot.infra.kv import InMemoryKV
from quanta_bot.infra.main_service import FakeCommentTreeFetcher, FakeReplyWriter
from quanta_bot.pipeline.pipeline import PipelineDeps
from quanta_bot.pipeline.ports import RunTrace


class SpyTracer:
    def __init__(self) -> None:
        self.traces: list[RunTrace] = []

    async def record(self, trace: RunTrace) -> None:
        self.traces.append(trace)


class StubMessage:
    """aio-pika IncomingMessage 的最小替身（body + ack）。"""

    def __init__(self, body: bytes) -> None:
        self.body = body
        self.acked = False

    async def ack(self) -> None:
        self.acked = True


def _deps(tmp_path) -> tuple[PipelineDeps, SQLiteAudit, FakeReplyWriter, InMemoryKV]:
    audit = SQLiteAudit(str(tmp_path / "d.db"))
    writer = FakeReplyWriter()
    kv = InMemoryKV()
    deps = PipelineDeps(
        kv=kv,
        audit=audit,
        reply_writer=writer,
        llm=FakeLLM(),
        tracer=SpyTracer(),
        control_plane=ControlPlane(kv),
        comment_tree=FakeCommentTreeFetcher(),
    )
    return deps, audit, writer, kv


def _msg(comment_id: int, content: str) -> bytes:
    return json.dumps(
        {
            "eventId": f"evt-{comment_id}",
            "commentId": comment_id,
            "postId": 99,
            "commenterUserId": 5,
            "commentContent": content,
            "mentionedBot": True,
            "botTriggerKind": "mentioned",
        }
    ).encode()


async def test_handle_valid_message_runs_pipeline_and_acks(tmp_path) -> None:
    """契约消息 → pipeline 跑通（replied）+ ack。"""
    deps, audit, writer, kv = _deps(tmp_path)
    consumer = CommentEventConsumer("amqp://x", deps, ControlPlane(kv))
    msg = StubMessage(_msg(1, "@QuantaBot hi"))
    await consumer._handle(msg)
    assert msg.acked is True
    assert len(writer.written) == 1
    entries = await audit.fetch_entries()
    assert entries[-1].decision == "replied"


async def test_handle_contract_violation_drops_and_audits(tmp_path) -> None:
    """毒丸（契约不符）→ 丢弃 + failed 留痕（comment_id=0 哨兵）+ ack（不阻塞队列）。"""
    deps, audit, writer, kv = _deps(tmp_path)
    consumer = CommentEventConsumer("amqp://x", deps, ControlPlane(kv))
    msg = StubMessage(b'{"commentId": 1}')
    await consumer._handle(msg)
    assert msg.acked is True
    assert writer.written == []
    entries = await audit.fetch_entries()
    assert entries[-1].decision == "failed"
    assert entries[-1].comment_id == 0


async def test_handle_pipeline_crash_bailed_out_as_failed(tmp_path) -> None:
    """管线未覆盖异常 → 消费兜底记 failed + ack（静默不回红线）。"""

    class ExplodingPipelineDeps(PipelineDeps):
        pass

    deps, audit, writer, kv = _deps(tmp_path)
    consumer = CommentEventConsumer("amqp://x", deps, ControlPlane(kv))

    # 注入一个会炸的 audit（模拟管线深处意外异常）：正常决策出口（replied）炸，
    # 消费兜底 failed 透传真 audit——闭包引用替换前捕获的真 audit，防自引用递归
    real_audit = audit

    class ExplodingAudit:
        async def record(self, entry):
            if entry.decision == "replied":
                raise RuntimeError("unexpected crash")
            await real_audit.record(entry)

    deps.audit = ExplodingAudit()
    msg = StubMessage(_msg(2, "@QuantaBot hi"))
    await consumer._handle(msg)
    assert msg.acked is True
    entries = await audit.fetch_entries()
    assert entries[-1].decision == "failed"
    assert "消费者兜底" in entries[-1].reason


async def test_handle_pauses_while_kill_enabled(tmp_path) -> None:
    """kill 置位 → 消息持有不 ack 不处理；解除后继续处理（G5 暂停消费）。"""
    deps, audit, writer, kv = _deps(tmp_path)
    cp = ControlPlane(kv)
    consumer = CommentEventConsumer("amqp://x", deps, cp, poll_seconds=0.01)
    await kv.set(SWITCH_KILL_KEY, "true", ttl_seconds=60)
    await cp.refresh()
    msg = StubMessage(_msg(3, "@QuantaBot hi"))
    task = asyncio.create_task(consumer._handle(msg))
    await asyncio.sleep(0.1)
    assert msg.acked is False and writer.written == []  # 暂停：零新回复
    await kv.set(SWITCH_KILL_KEY, "false", ttl_seconds=60)
    await cp.refresh()
    await asyncio.wait_for(task, timeout=5)
    assert msg.acked is True and len(writer.written) == 1
