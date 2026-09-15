"""全链路集成测试（fake 依赖 + 真 SQLite 审计 + 真幂等/预检/成本/控制面逻辑）。

覆盖：M1 三条验收演进版 + M2 新增（kill 短路 / failed 分支 / 成本累计 / trace 埋点）。
"""

from datetime import UTC, datetime

from quanta_bot.crosscutting import budget
from quanta_bot.crosscutting.killswitch import SWITCH_KILL_KEY, ControlPlane
from quanta_bot.infra.audit_db import SQLiteAudit
from quanta_bot.infra.deepseek import FakeLLM
from quanta_bot.infra.kv import InMemoryKV
from quanta_bot.infra.main_service import FakeCommentTreeFetcher, FakeReplyWriter
from quanta_bot.pipeline.pipeline import PipelineDeps, run
from quanta_bot.pipeline.ports import LLMClientError, ReplyWriteError, RunTrace
from quanta_bot.pipeline.trigger import TriggerEvent


class SpyTracer:
    """trace 间谍（断言上报形状——不mock业务，只观测）。"""

    def __init__(self) -> None:
        self.traces: list[RunTrace] = []

    async def record(self, trace: RunTrace) -> None:
        self.traces.append(trace)


class ExplodingLLM:
    """模拟 DeepSeek 超时/故障。"""

    async def complete(self, system: str, user: str):
        raise LLMClientError("DeepSeek 调用失败：timeout")


def _deps(
    tmp_path, llm=None, tracer=None, writer=None, kv=None
) -> tuple[PipelineDeps, SQLiteAudit, FakeReplyWriter, SpyTracer, InMemoryKV]:
    audit = SQLiteAudit(str(tmp_path / "d.db"))
    w = writer or FakeReplyWriter()
    t = tracer or SpyTracer()
    k = kv or InMemoryKV()
    deps = PipelineDeps(
        kv=k,
        audit=audit,
        reply_writer=w,
        llm=llm or FakeLLM(),
        tracer=t,
        control_plane=ControlPlane(k),
        comment_tree=FakeCommentTreeFetcher(),
    )
    return deps, audit, w, t, k


def _event(comment_id: int, content: str, mentioned_bot: bool = True) -> TriggerEvent:
    return TriggerEvent(
        event_id=f"evt-{comment_id}",
        comment_id=comment_id,
        post_id=99,
        commenter_user_id=5,
        content=content,
        mentioned_bot=mentioned_bot,
    )


async def test_reply_flows_to_writer_with_audit_and_trace(tmp_path) -> None:
    """验收 1 演进：@ 消息 → 写库 1 条（带 AI 标识）+ 决策日志 replied + trace 含成本。"""
    deps, audit, writer, tracer, kv = _deps(tmp_path)
    result = await run(_event(1, "@QuantaBot 帮我选课"), deps)
    assert result == "replied"
    assert len(writer.written) == 1
    assert writer.written[0].content.startswith("[QuantaBot·AI 学长]")
    assert writer.written[0].reply_to_comment_id == 1
    entries = await audit.fetch_entries()
    assert [e.decision for e in entries] == ["replied"]
    assert entries[0].mode == "生活玩梗"
    # 成本键：FakeLLM 500/100 → estimate 8 厘
    today = datetime.now(UTC).date()
    assert await budget.read_cost(kv, today) == 8
    assert "8 厘" in entries[0].reason
    # trace 埋点
    assert [t.decision for t in tracer.traces] == ["replied"]
    assert tracer.traces[0].cost_li == 8
    assert tracer.traces[0].generated_content is not None


async def test_duplicate_comment_id_not_rewritten(tmp_path) -> None:
    """验收 2：重投同 comment_id → 写库不重复 + skipped_idempotent。"""
    deps, audit, writer, _, _ = _deps(tmp_path)
    ev = _event(1, "@QuantaBot hi")
    await run(ev, deps)
    await run(ev, deps)
    assert len(writer.written) == 1
    entries = await audit.fetch_entries()
    assert [e.decision for e in entries] == ["replied", "skipped_idempotent"]


async def test_sensitive_content_blocked_zero_reply(tmp_path) -> None:
    """验收 3：违规文本 → 预检拦截、写库零调用、rejected_moderation。"""
    deps, audit, writer, tracer, _ = _deps(tmp_path)
    result = await run(_event(2, "@QuantaBot 这里有测试敏感词"), deps)
    assert result == "rejected_moderation"
    assert writer.written == []
    entries = await audit.fetch_entries()
    assert [e.decision for e in entries] == ["rejected_moderation"]
    assert tracer.traces[0].generated_content is None


async def test_not_mentioned_skipped_before_idempotency(tmp_path) -> None:
    """未命中 @ → 链路不进入（幂等键不落）。"""
    deps, audit, writer, _, kv = _deps(tmp_path)
    result = await run(_event(3, "纯聊天没 @", mentioned_bot=False), deps)
    assert result == "skipped_not_mentioned"
    assert writer.written == []
    assert await kv.get("quantabot:idem:3") is None


async def test_structured_mention_flag_is_primary(tmp_path) -> None:
    """C-4 主判定：mentioned_bot=False 且文本无 @ → 不进链路；mentioned_bot=False 但文本兜底命中 → 进链路（降级路径）。"""
    deps, audit, writer, _, _ = _deps(tmp_path)
    # 兜底命中：结构化标记缺失（前端旧版本），文本含 @
    result = await run(_event(7, "@QuantaBot 文本兜底命中", mentioned_bot=False), deps)
    assert result == "replied"
    # 双未命中
    result = await run(_event(8, "没有标记也没有艾特", mentioned_bot=False), deps)
    assert result == "skipped_not_mentioned"


async def test_kill_switch_short_circuits_before_idempotency(tmp_path) -> None:
    """G5（M2 生效部分）：kill 置位 → 短路不回，幂等键不占（置位期间零新回复）。"""
    deps, audit, writer, _, kv = _deps(tmp_path)
    await kv.set(SWITCH_KILL_KEY, "true", ttl_seconds=60)
    await deps.control_plane.refresh()
    result = await run(_event(4, "@QuantaBot hi"), deps)
    assert result == "skipped_killswitch"
    assert writer.written == []
    assert await kv.get("quantabot:idem:4") is None
    entries = await audit.fetch_entries()
    assert [e.decision for e in entries] == ["skipped_killswitch"]


async def test_llm_failure_records_failed_zero_reply(tmp_path) -> None:
    """红线 §0.3：LLM 失败 → failed 静默不回（写库零调用，日志与 trace 留痕）。"""
    deps, audit, writer, tracer, _ = _deps(tmp_path, llm=ExplodingLLM())
    result = await run(_event(5, "@QuantaBot hi"), deps)
    assert result == "failed"
    assert writer.written == []
    entries = await audit.fetch_entries()
    assert entries[0].decision == "failed"
    assert "timeout" in entries[0].reason
    assert tracer.traces[0].error == "DeepSeek 调用失败：timeout"
    assert tracer.traces[0].decision == "failed"


async def test_reply_write_failure_records_failed(tmp_path) -> None:
    """写库失败（含真模式占位 writer）→ failed 静默不回。"""

    class ExplodingWriter:
        async def write_reply(self, reply) -> None:
            raise ReplyWriteError("写库真客户端未接入（P0-5）")

    deps, audit, _, tracer, _ = _deps(tmp_path, writer=ExplodingWriter())
    result = await run(_event(6, "@QuantaBot hi"), deps)
    assert result == "failed"
    entries = await audit.fetch_entries()
    assert entries[0].decision == "failed"
    assert tracer.traces[0].decision == "failed"
