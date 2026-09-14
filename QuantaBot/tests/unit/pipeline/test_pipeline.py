"""全链路集成测试（fake 依赖 + 真 SQLite 审计 + 真幂等/预检）。

覆盖总计划 M1 验收三条：正常回复落 fake 写库、幂等重投不重复、违规文本零回复。
"""

from quanta_bot.infra.audit_db import SQLiteAudit
from quanta_bot.infra.kv import InMemoryKV
from quanta_bot.infra.main_service import FakeReplyWriter
from quanta_bot.pipeline.pipeline import PipelineDeps, run
from quanta_bot.pipeline.trigger import TriggerEvent


def _deps(tmp_path) -> tuple[PipelineDeps, SQLiteAudit, FakeReplyWriter]:
    """真实 SQLite 审计 + 内存 fake kv/写库（与 composition 的 fake_mode 形态一致）。"""
    audit = SQLiteAudit(str(tmp_path / "d.db"))
    writer = FakeReplyWriter()
    deps = PipelineDeps(kv=InMemoryKV(), audit=audit, reply_writer=writer)
    return deps, audit, writer


def _event(comment_id: int, content: str) -> TriggerEvent:
    return TriggerEvent(comment_id=comment_id, post_id=99, author_user_id=5, content=content)


async def test_reply_flows_to_writer_with_audit(tmp_path) -> None:
    """验收 1：含 @ 消息 → fake 写库收到 1 条（带 AI 标识）+ 决策日志 1 条 replied + 幂等键落库。"""
    deps, audit, writer = _deps(tmp_path)
    result = await run(_event(1, "@QuantaBot 帮我选课"), deps)
    assert result == "replied"
    assert len(writer.written) == 1
    assert "AI" in writer.written[0].content
    assert writer.written[0].reply_to_comment_id == 1
    entries = await audit.fetch_entries()
    assert [e.decision for e in entries] == ["replied"]
    assert entries[0].mode == "生活玩梗"


async def test_duplicate_comment_id_not_rewritten(tmp_path) -> None:
    """验收 2：重投同一 comment_id → 写库调用数不增加 + 记 skipped_idempotent。"""
    deps, audit, writer = _deps(tmp_path)
    ev = _event(1, "@QuantaBot hi")
    await run(ev, deps)
    await run(ev, deps)
    assert len(writer.written) == 1  # 关键断言：不重复回复
    entries = await audit.fetch_entries()
    assert [e.decision for e in entries] == ["replied", "skipped_idempotent"]


async def test_sensitive_content_blocked_zero_reply(tmp_path) -> None:
    """验收 3：违规文本 → 规则预检拦截、写库零调用、决策日志记 rejected_moderation。"""
    deps, audit, writer = _deps(tmp_path)
    result = await run(_event(2, "@QuantaBot 这里有测试敏感词"), deps)
    assert result == "rejected_moderation"
    assert writer.written == []  # 关键断言：零回复（静默优于乱回）
    entries = await audit.fetch_entries()
    assert [e.decision for e in entries] == ["rejected_moderation"]


async def test_not_mentioned_skipped_before_idempotency(tmp_path) -> None:
    """未命中 @ → 不进入链路（幂等键不落，审计记 skipped_not_mentioned）。"""
    deps, audit, writer = _deps(tmp_path)
    result = await run(_event(3, "纯聊天没 @"), deps)
    assert result == "skipped_not_mentioned"
    assert writer.written == []
    entries = await audit.fetch_entries()
    assert [e.decision for e in entries] == ["skipped_not_mentioned"]
