"""全链路集成测试（fake 依赖 + 真 SQLite 审计 + 真 幂等/预检/成本/控制面/记忆/对话链逻辑）。

覆盖：M1 三条验收演进版 + M2 新增（kill 短路 / failed 分支 / 成本累计 / trace 埋点）
      + M3 新增（硬规则拉取前拦截 / 一车四用决策 + 记忆四态落库 + 对话链 append /
      记忆落库失败不回滚回复 / RunTrace 截断留痕与精选记忆归因）。
"""

import tempfile
from datetime import UTC, datetime
from pathlib import Path

from quanta_bot.crosscutting import budget
from quanta_bot.crosscutting.killswitch import SWITCH_KILL_KEY, ControlPlane
from quanta_bot.infra.audit_db import SQLiteAudit
from quanta_bot.infra.deepseek import FakeLLM
from quanta_bot.infra.kv import InMemoryKV
from quanta_bot.infra.main_service import FakeCommentTreeFetcher, FakeReplyWriter
from quanta_bot.memory import dialogue
from quanta_bot.memory.ports import HashEmbeddingClient, MemoryRecord
from quanta_bot.memory.user_memory import InMemoryUserMemoryStore
from quanta_bot.pipeline.context import LLMSummarizer
from quanta_bot.pipeline.persona import PersonaLibrary
from quanta_bot.pipeline.pipeline import PipelineDeps, run
from quanta_bot.pipeline.ports import (
    CommentNode,
    LLMClientError,
    PostSummary,
    PostThread,
    ReplyWriteError,
    RunTrace,
)
from quanta_bot.pipeline.trigger import TriggerEvent

# 合法决策 JSON 剧本（决策层 v2 真实化后，replied 路径首位 LLM 响应必须是它）
_DECISION_JSON = (
    '{"should_reply": true, "mode": "生活玩梗", "confidence": 0.9, "reason": "真诚求助"}'
)


class SpyTracer:
    """trace 间谍（断言上报形状——不 mock 业务，只观测）。"""

    def __init__(self) -> None:
        self.traces: list[RunTrace] = []

    async def record(self, trace: RunTrace) -> None:
        self.traces.append(trace)


class ExplodingLLM:
    """模拟 DeepSeek 超时/故障（兼容决策层轻量调用的 json_mode/max_tokens 参数）。"""

    async def complete(
        self, system: str, user: str, *, json_mode: bool = False, max_tokens: int | None = None
    ):
        raise LLMClientError("DeepSeek 调用失败：timeout")


class CountingFetcher:
    """评论树拉取计数间谍（硬规则前置断言：拦截发生时 fetch_context/fetch_floors 零调用）。"""

    def __init__(self) -> None:
        self.count = 0

    async def fetch_context(self, event: TriggerEvent) -> PostThread:
        self.count += 1
        return PostThread(
            post=PostSummary(post_id=event.post_id, author_user_id=1, title="计数", content="计数")
        )

    async def fetch_floors(self, post_id: int) -> tuple[CommentNode, ...]:
        self.count += 1
        return ()


class FailingMemoryStore:
    """apply_ops 必抛的 fake 记忆库（验证 ⑫ 步记忆落库失败不阻断已成功回复）。"""

    async def recall(
        self, user_id: int, persona_version: str, query_text: str, limit: int
    ) -> tuple[MemoryRecord, ...]:
        return ()

    async def negative_feedback(
        self, user_id: int, persona_version: str
    ) -> tuple[MemoryRecord, ...]:
        return ()

    async def apply_ops(self, user_id: int, persona_version: str, ops) -> None:
        raise RuntimeError("记忆库写失败（测试注入）")


def _m3_deps(
    llm=None,
    tree=None,
    *,
    tracer=None,
    writer=None,
    kv=None,
    preset_memory_id: str | None = None,
    tmp_path: Path | None = None,
) -> PipelineDeps:
    """M3 全 fake 依赖装配（audit=真 SQLite；记忆可预置指定 id 记录；楼层缺省恒空=摘要零调用）。"""
    store_kv = kv or InMemoryKV()
    store_llm = llm or FakeLLM()
    persona = PersonaLibrary()
    memory_store = InMemoryUserMemoryStore(HashEmbeddingClient())
    if preset_memory_id is not None:
        # 预置记忆：fake 存储无公开预置 API（memory_id 由 uuid4 生成），测试直接注入记录
        memory_store._records.append(
            MemoryRecord(
                memory_id=preset_memory_id,
                user_id=42,
                type="project",
                content="用户主修机械工程，常来问选课",
                why="测试预置",
                created_at=datetime.now(UTC),
                persona_version=persona.persona_version,
            )
        )
    audit_dir = tmp_path or Path(tempfile.mkdtemp(prefix="quantabot-test-"))  # 无 fixture 用例兜底
    return PipelineDeps(
        kv=store_kv,
        audit=SQLiteAudit(str(audit_dir / "m3.db")),
        reply_writer=writer or FakeReplyWriter(),
        llm=store_llm,
        tracer=tracer or SpyTracer(),
        control_plane=ControlPlane(store_kv),
        comment_tree=tree or FakeCommentTreeFetcher(),
        persona=persona,
        memory_store=memory_store,
        summarizer=LLMSummarizer(store_llm),
    )


def _event(
    content: str,
    comment_id: int = 1,
    *,
    post_id: int = 10,
    commenter_user_id: int = 42,
    mentioned_bot: bool = True,
) -> TriggerEvent:
    """触发事件构造（M3 口径默认 post_id=10 / commenter_user_id=42——记忆与对话链断言锚点）。"""
    return TriggerEvent(
        event_id=f"evt-{comment_id}",
        comment_id=comment_id,
        post_id=post_id,
        commenter_user_id=commenter_user_id,
        content=content,
        mentioned_bot=mentioned_bot,
    )


def _decision_json_with_add() -> str:
    """带 ADD 四态的决策 JSON 剧本（记忆落库路径用）。"""
    return (
        '{"should_reply": true, "mode": "情绪陪伴", "confidence": 0.9, "reason": "求安慰",'
        ' "memory_ops": [{"op": "ADD", "type": "project",'
        ' "content": "用户在准备 2026 春季学期期末考试", "why": "动态"}]}'
    )


def captured_trace(deps: PipelineDeps) -> RunTrace:
    """取最近一次上报的 RunTrace（SpyTracer 轨迹末位——单 run 测试口径）。"""
    assert deps.tracer.traces, "链路未上报任何 RunTrace"
    return deps.tracer.traces[-1]


async def test_full_m3_flow_with_memory_and_assembly() -> None:
    """M3 全链路（fake）：决策一车四用→记忆精选/四态→四通道组装→生成→写库→记忆落库+对话链。"""
    decision_json = (
        '{"should_reply": true, "mode": "情绪陪伴", "confidence": 0.9, "reason": "求安慰",'
        ' "memory_selection": ["m1"], "memory_ops": [{"op": "ADD", "type": "project",'
        ' "content": "用户在准备 2026 春季学期期末考试", "why": "动态"}]}'
    )
    llm = FakeLLM(responses=[decision_json, "抱抱，先拆个小计划"])  # 决策+生成两次调用
    deps = _m3_deps(llm, preset_memory_id="m1")  # 辅助构造：预置一条 m1 记忆 + 全 fake 依赖
    decision = await run(_event("@QuantaBot 期末要挂科了好焦虑"), deps)
    assert decision == "replied"
    store = deps.memory_store
    hits = await store.recall(42, deps.persona.persona_version, "期末", limit=5)
    assert any("期末考试" in record.content for record in hits)  # 四态 ADD 已落库
    turns = await dialogue.read_chain(deps.kv, 10)
    assert len(turns) == 1 and "抱抱" in turns[0].reply_content  # 对话链 append
    assert deps.reply_writer.written  # 写库收到 1 条（沿用 M2 fake 断言口径）


async def test_low_value_hard_rule_skips_before_fetch() -> None:
    """硬规则在拉取前拦截：零 LLM 调用、零评论树调用、决策=skipped_low_value。"""
    llm = FakeLLM()
    fetcher_calls = CountingFetcher()
    deps = _m3_deps(llm, fetcher_calls)
    decision = await run(_event("@QuantaBot 哈哈哈哈哈"), deps)
    assert decision == "skipped_low_value"
    assert llm.calls == [] and fetcher_calls.count == 0


async def test_decision_not_worth_replying_skips_silently() -> None:
    """LLM 判不值得回 → skipped_decision（决策日志诚实留痕，静默不回）。"""
    llm = FakeLLM(
        responses=[
            '{"should_reply": false, "mode": "生活玩梗", "confidence": 0.7, "reason": "纯凑热闹"}'
        ]
    )
    decision = await run(_event("@QuantaBot 今天食堂好像换了新窗口"), _m3_deps(llm))
    assert decision == "skipped_decision"


async def test_memory_apply_failure_does_not_rollback_reply() -> None:
    """记忆四态落库失败：回复已成功不回滚，WARNING 留痕（决策仍 replied）。"""
    deps = _m3_deps(FakeLLM(responses=[_decision_json_with_add(), "回复"]))
    deps.memory_store = FailingMemoryStore()  # apply_ops 抛异常的 fake
    decision = await run(_event("@QuantaBot 期末求安慰"), deps)
    assert decision == "replied"  # 记忆失败不阻断已成功回复


async def test_trace_records_truncations_and_selection() -> None:
    """RunTrace 扩展：截断留痕 + 精选记忆 id + persona_version 进轨迹（对质与归因）。"""
    # 超预算场景：12000 字主楼超 B 通道预算 → 保头尾掐中间并留痕（远区恒空=摘要零调用）
    huge_post = PostSummary(
        post_id=10, author_user_id=1, title="超长主楼", content="主楼内容" * 3000
    )
    decision_json = (
        '{"should_reply": true, "mode": "生活玩梗", "confidence": 0.9, "reason": "总结请求",'
        ' "memory_selection": ["m1"]}'
    )
    deps = _m3_deps(
        FakeLLM(responses=[decision_json, "已总结"]),
        tree=FakeCommentTreeFetcher(post=huge_post),
        preset_memory_id="m1",
    )
    decision = await run(_event("@QuantaBot 这帖子太长了帮我总结下"), deps)
    assert decision == "replied"
    trace = captured_trace(deps)
    assert trace.truncations  # 截断留痕非空
    assert any(item.channel == "B" and "主楼" in item.what for item in trace.truncations)
    assert trace.memory_selected_ids == ("m1",)  # 精选记忆 id 在场
    assert trace.persona_version == deps.persona.persona_version  # 人格版本指纹在场


async def test_reply_flows_to_writer_with_audit_and_trace(tmp_path) -> None:
    """验收 1 演进：@ 消息 → 写库 1 条（带 AI 标识）+ 决策日志 replied + trace 含成本。"""
    deps = _m3_deps(
        FakeLLM(responses=[_DECISION_JSON, "选课方面我可以帮你梳理～"]), tmp_path=tmp_path
    )
    result = await run(_event("@QuantaBot 帮我选课", comment_id=1), deps)
    assert result == "replied"
    assert len(deps.reply_writer.written) == 1
    assert deps.reply_writer.written[0].content.startswith("[QuantaBot·AI 学长]")
    assert deps.reply_writer.written[0].reply_to_comment_id == 1
    entries = await deps.audit.fetch_entries()
    assert [entry.decision for entry in entries] == ["replied"]
    assert entries[0].mode == "生活玩梗"
    # 成本键：FakeLLM 500/100 → estimate 8 厘
    today = datetime.now(UTC).date()
    assert await budget.read_cost(deps.kv, today) == 8
    assert "8 厘" in entries[0].reason
    # trace 埋点
    assert [trace.decision for trace in deps.tracer.traces] == ["replied"]
    assert deps.tracer.traces[0].cost_li == 8
    assert deps.tracer.traces[0].generated_content is not None


async def test_duplicate_comment_id_not_rewritten(tmp_path) -> None:
    """验收 2：重投同 comment_id → 写库不重复 + skipped_idempotent。"""
    deps = _m3_deps(FakeLLM(responses=[_DECISION_JSON, "重复投递测试回复"]), tmp_path=tmp_path)
    event = _event("@QuantaBot 帮我选课", comment_id=1)
    await run(event, deps)
    await run(event, deps)
    assert len(deps.reply_writer.written) == 1
    entries = await deps.audit.fetch_entries()
    assert [entry.decision for entry in entries] == ["replied", "skipped_idempotent"]


async def test_sensitive_content_blocked_zero_reply(tmp_path) -> None:
    """验收 3：违规文本 → 预检拦截、写库零调用、rejected_moderation。"""
    deps = _m3_deps(tmp_path=tmp_path)
    result = await run(_event("@QuantaBot 这里有测试敏感词", comment_id=2), deps)
    assert result == "rejected_moderation"
    assert deps.reply_writer.written == []
    entries = await deps.audit.fetch_entries()
    assert [entry.decision for entry in entries] == ["rejected_moderation"]
    assert deps.tracer.traces[0].generated_content is None


async def test_not_mentioned_skipped_before_idempotency(tmp_path) -> None:
    """未命中 @ → 链路不进入（幂等键不落）。"""
    deps = _m3_deps(tmp_path=tmp_path)
    result = await run(_event("纯聊天没 @", comment_id=3, mentioned_bot=False), deps)
    assert result == "skipped_not_mentioned"
    assert deps.reply_writer.written == []
    assert await deps.kv.get("quantabot:idem:3") is None


async def test_structured_mention_flag_is_primary(tmp_path) -> None:
    """C-4 主判定：mentioned_bot=False 且文本无 @ → 不进链路；mentioned_bot=False 但文本兜底命中 → 进链路（降级路径）。"""
    deps = _m3_deps(FakeLLM(responses=[_DECISION_JSON, "文本兜底路径测试回复"]), tmp_path=tmp_path)
    # 兜底命中：结构化标记缺失（前端旧版本），文本含 @
    result = await run(_event("@QuantaBot 文本兜底命中", comment_id=7, mentioned_bot=False), deps)
    assert result == "replied"
    # 双未命中
    result = await run(_event("没有标记也没有艾特", comment_id=8, mentioned_bot=False), deps)
    assert result == "skipped_not_mentioned"


async def test_kill_switch_short_circuits_before_idempotency(tmp_path) -> None:
    """G5（M2 生效部分）：kill 置位 → 短路不回，幂等键不占（置位期间零新回复）。"""
    deps = _m3_deps(tmp_path=tmp_path)
    await deps.kv.set(SWITCH_KILL_KEY, "true", ttl_seconds=60)
    await deps.control_plane.refresh()
    result = await run(_event("@QuantaBot hi", comment_id=4), deps)
    assert result == "skipped_killswitch"
    assert deps.reply_writer.written == []
    assert await deps.kv.get("quantabot:idem:4") is None
    entries = await deps.audit.fetch_entries()
    assert [entry.decision for entry in entries] == ["skipped_killswitch"]


async def test_llm_failure_records_failed_zero_reply(tmp_path) -> None:
    """红线 §0.3：LLM 失败 → failed 静默不回（写库零调用，日志与 trace 留痕）。"""
    deps = _m3_deps(ExplodingLLM(), tmp_path=tmp_path)
    result = await run(_event("@QuantaBot 帮我看看这道题", comment_id=5), deps)
    assert result == "failed"
    assert deps.reply_writer.written == []
    entries = await deps.audit.fetch_entries()
    assert entries[0].decision == "failed"
    assert "timeout" in entries[0].reason
    assert deps.tracer.traces[0].error == "DeepSeek 调用失败：timeout"
    assert deps.tracer.traces[0].decision == "failed"


async def test_reply_write_failure_records_failed(tmp_path) -> None:
    """写库失败（含真模式占位 writer）→ failed 静默不回。"""

    class ExplodingWriter:
        async def write_reply(self, reply) -> None:
            raise ReplyWriteError("写库真客户端未接入（P0-5）")

    deps = _m3_deps(
        FakeLLM(responses=[_DECISION_JSON, "写库失败测试回复"]),
        writer=ExplodingWriter(),
        tmp_path=tmp_path,
    )
    result = await run(_event("@QuantaBot 帮我看看这道题", comment_id=6), deps)
    assert result == "failed"
    entries = await deps.audit.fetch_entries()
    assert entries[0].decision == "failed"
    assert deps.tracer.traces[0].decision == "failed"
