"""M5 控制闸回归：真实管线、KV 和审计，外部模型/记忆使用端口假实现。"""

from datetime import UTC, datetime

import pytest
from tests.unit.pipeline.test_pipeline import (
    _DECISION_JSON,
    FailingMemoryStore,
    _decision_json_retrieval,
    _decision_json_with_add,
    _event,
    _m3_deps,
    captured_trace,
)

from quanta_bot.crosscutting.breaker import BreakerSet, CircuitBreaker
from quanta_bot.crosscutting.killswitch import SWITCH_GRAYLIST_KEY, SWITCH_PERSONA_VERSION_KEY
from quanta_bot.infra.deepseek import FakeLLM
from quanta_bot.pipeline.pipeline import run
from quanta_bot.pipeline.ports import LLMClientError


async def test_graylist_blocks_without_model_or_write_and_retains_dirty_snapshot(tmp_path):
    deps = _m3_deps(FakeLLM(responses=[_DECISION_JSON, "收到"]), tmp_path=tmp_path)
    await deps.kv.set(SWITCH_GRAYLIST_KEY, "[7]", 60)
    await deps.control_plane.refresh()
    assert await run(_event("@框框 帮我解释选课", 2001), deps) == "skipped_graylist"
    assert deps.llm.calls == [] and deps.reply_writer.written == []
    await deps.kv.set(SWITCH_GRAYLIST_KEY, "broken", 60)
    await deps.control_plane.refresh()
    assert deps.control_plane.snapshot.graylist == (7,)
    assert await run(_event("@框框 帮我解释选课", 2002), deps) == "skipped_graylist"


async def test_remote_version_is_memory_namespace_and_trace_has_processing_time(tmp_path):
    deps = _m3_deps(FakeLLM(responses=[_decision_json_with_add(), "先休息一下"]), tmp_path=tmp_path)
    original_prompt = deps.persona.system_prompt("情绪陪伴")
    await deps.kv.set(SWITCH_PERSONA_VERSION_KEY, "m5-test-version", 60)
    await deps.control_plane.refresh()
    assert await run(_event("@框框 考试很焦虑怎么办", 2003), deps) == "replied"
    trace = captured_trace(deps)
    assert trace.persona_version == "m5-test-version"
    assert trace.duration_ms >= 0
    assert all(
        trace.stage_ms[stage] >= 0 for stage in ("decision", "context", "generation", "write")
    )
    assert deps.persona.system_prompt("情绪陪伴") == original_prompt
    hits = await deps.memory_store.recall(42, "m5-test-version", "考试", 5)
    assert len(hits) == 1
    assert await deps.memory_store.recall(42, deps.persona.persona_version, "考试", 5) == ()
    row = (await deps.audit.fetch_entries())[-1]
    assert row.duration_ms == trace.duration_ms and row.cost_li == trace.cost_li


async def test_shared_post_attempt_quota_blocks_other_user_and_duplicate_has_audit(tmp_path):
    deps = _m3_deps(FakeLLM(responses=[_DECISION_JSON, "收到"] * 3), tmp_path=tmp_path)
    for index in range(3):
        assert await run(_event("@框框 帮我解释选课", 2010 + index), deps) == "replied"
    assert (
        await run(_event("@框框 帮我解释选课", 2020, commenter_user_id=7), deps)
        == "skipped_rate_limit"
    )
    assert await run(_event("@框框 帮我解释选课", 2010), deps) == "skipped_idempotent"
    assert len(deps.reply_writer.written) == 3
    assert (await deps.audit.fetch_entries())[-1].decision == "skipped_idempotent"


async def test_memory_write_failures_accumulate_once_per_run_and_open_skips_writes(tmp_path):
    class CountingMemory(FailingMemoryStore):
        def __init__(self):
            self.writes = 0

        async def apply_ops(self, user_id, persona_version, ops):
            self.writes += 1
            await super().apply_ops(user_id, persona_version, ops)

    deps = _m3_deps(FakeLLM(responses=[_decision_json_with_add(), "收到"] * 3), tmp_path=tmp_path)
    deps.breakers = BreakerSet(memory=CircuitBreaker("memory", 2, 30))
    deps.memory_store = CountingMemory()
    for index in range(2):
        assert await run(_event("@框框 考试很焦虑怎么办", 2030 + index), deps) == "replied"
    assert deps.breakers.memory.state == "open"
    assert await run(_event("@框框 考试很焦虑怎么办", 2032), deps) == "replied"
    assert deps.memory_store.writes == 2
    assert captured_trace(deps).memory_degraded is True


async def test_rag_failures_share_memory_breaker_and_open_skips_retrieval(tmp_path):
    class FailingRetriever:
        def __init__(self):
            self.calls = 0

        async def retrieve(self, query, limit=3, doc_kind=None):
            self.calls += 1
            raise RuntimeError("qdrant unavailable")

    deps = _m3_deps(
        FakeLLM(responses=[_decision_json_retrieval(), "请查看官方政策"] * 3), tmp_path=tmp_path
    )
    deps.breakers = BreakerSet(memory=CircuitBreaker("memory", 2, 30))
    deps.retriever = FailingRetriever()
    for index in range(3):
        assert await run(_event("@框框 奖助学金政策怎么算", 2040 + index), deps) == "replied"
    assert deps.retriever.calls == 2
    assert deps.breakers.memory.state == "open"
    assert captured_trace(deps).retrieval_degraded is True


async def test_successful_silent_decision_resets_llm_failures(tmp_path):
    silent = '{"should_reply": false, "mode": "生活玩梗", "confidence": 0.9, "reason": "已有回答"}'
    deps = _m3_deps(FakeLLM(responses=[silent]), tmp_path=tmp_path)
    deps.breakers.llm.record_failure()
    assert await run(_event("@框框 帮我解释选课", 2050), deps) == "skipped_decision"
    for _ in range(4):
        deps.breakers.llm.record_failure()
    assert deps.breakers.llm.state == "closed"


@pytest.mark.parametrize("operation", ["read_chain", "append_turn"])
async def test_dialogue_failure_marks_memory_domain_and_trace(tmp_path, monkeypatch, operation):
    from quanta_bot.memory import dialogue

    async def fail(*args, **kwargs):
        raise RuntimeError("dialogue storage unavailable")

    monkeypatch.setattr(dialogue, operation, fail)
    deps = _m3_deps(FakeLLM(responses=[_DECISION_JSON, "收到"]), tmp_path=tmp_path)
    deps.breakers = BreakerSet(memory=CircuitBreaker("memory", 1, 30))
    assert await run(_event("@框框 帮我解释选课", 2060), deps) == "replied"
    assert deps.breakers.memory.state == "open"
    assert captured_trace(deps).memory_degraded is True


async def test_run_pins_cost_day_across_utc_midnight(tmp_path, monkeypatch):
    from quanta_bot.crosscutting.budget import cost_key
    from quanta_bot.pipeline import pipeline

    before = datetime(2026, 9, 26, 23, 59, 59, tzinfo=UTC)
    after = datetime(2026, 9, 27, 0, 0, 1, tzinfo=UTC)
    instants = iter([before, after])

    class Clock:
        @staticmethod
        def now(zone):
            return next(instants, after)

    monkeypatch.setattr(pipeline, "datetime", Clock)
    deps = _m3_deps(FakeLLM(responses=[_DECISION_JSON, "收到"]), tmp_path=tmp_path)
    assert await run(_event("@框框 帮我解释选课", 2070), deps) == "replied"
    assert await deps.kv.get(cost_key(before.date())) == str(captured_trace(deps).cost_li)
    assert await deps.kv.get(cost_key(after.date())) is None


async def test_failed_light_generation_still_records_selected_light_model(tmp_path):
    from quanta_bot.crosscutting.budget import cost_key

    class FailingLight:
        async def complete(self, *args, **kwargs):
            raise LLMClientError("light timeout")

    deps = _m3_deps(FakeLLM(responses=[_DECISION_JSON]), tmp_path=tmp_path)
    deps.llm_light = FailingLight()
    await deps.kv.set(cost_key(datetime.now(UTC).date()), "21000", 60)
    assert await run(_event("@框框 帮我解释选课", 2080), deps) == "failed"
    assert captured_trace(deps).light_model_used is True
