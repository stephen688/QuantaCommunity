"""tracing 行为测试：Null 空实现 + Langfuse 客户端上报形状（stub langfuse 对象，无网络）。"""

from quanta_bot.infra.tracing import LangfuseTracer, NullTracer
from quanta_bot.pipeline.ports import RunTrace


def _trace(**overrides) -> RunTrace:
    base = dict(
        comment_id=7,
        post_id=99,
        trigger_content="@QuantaBot hi",
        decision="replied",
        mode="生活玩梗",
        reason="链路完整",
        generated_content="[QuantaBot·AI 学长] 回复",
        prompt_tokens=500,
        completion_tokens=100,
        cost_li=8,
        daily_cost_li_after=16,
    )
    base.update(overrides)
    return RunTrace(**base)


async def test_null_tracer_noop() -> None:
    """NullTracer 降级空实现（不抛、不做事）。"""
    await NullTracer().record(_trace())


class _StubTraceHandle:
    def __init__(self) -> None:
        self.generation_calls: list[dict] = []

    def generation(self, **kwargs) -> None:
        self.generation_calls.append(kwargs)


class _StubLangfuse:
    def __init__(self) -> None:
        self.trace_calls: list[dict] = []
        self.handle = _StubTraceHandle()
        self.flushed = 0

    def trace(self, **kwargs) -> _StubTraceHandle:
        self.trace_calls.append(kwargs)
        return self.handle

    def flush(self) -> None:
        self.flushed += 1


async def test_langfuse_tracer_records_trace_and_generation() -> None:
    """上报形状：trace(name/input/output/metadata) + generation(usage 入 metadata)；异常被兜底。"""
    stub = _StubLangfuse()
    tracer = LangfuseTracer(public_key="pk", secret_key="sk", host="http://x", client=stub)
    await tracer.record(_trace())
    assert len(stub.trace_calls) == 1
    call = stub.trace_calls[0]
    assert call["name"] == "pipeline.run"
    assert call["id"] == "run-7"
    assert call["input"] == "@QuantaBot hi"
    assert len(stub.handle.generation_calls) == 1
    gen = stub.handle.generation_calls[0]
    assert gen["name"] == "generation"
    assert gen["metadata"]["cost_li"] == 8


async def test_langfuse_tracer_skips_generation_when_not_generated() -> None:
    """未进生成阶段（skip/failed/rejected）→ 只上报 trace 不上报 generation。"""
    stub = _StubLangfuse()
    tracer = LangfuseTracer("pk", "sk", "http://x", client=stub)
    await tracer.record(_trace(decision="skipped_idempotent", generated_content=None))
    assert stub.handle.generation_calls == []


async def test_langfuse_tracer_swallows_client_errors() -> None:
    """观测边界：Langfuse 任何故障只 WARNING 不抛（不阻断业务链路）。"""

    class _Exploding:
        def trace(self, **kwargs):
            raise RuntimeError("langfuse down")

    tracer = LangfuseTracer("pk", "sk", "http://x", client=_Exploding())
    await tracer.record(_trace())  # 不抛即通过
