"""tracing 行为测试：Null 空实现 + Langfuse 客户端上报形状（stub langfuse 对象，无网络）。

stub 按 langfuse 4.x SDK 形状建模：create_trace_id(seed) → start_as_current_observation
（context manager，退出自动 end root，end_on_exit=True）→ root.start_observation → child.end()。
"""

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


class _StubObservation:
    def __init__(self) -> None:
        self.child_calls: list[dict] = []
        self.ended = False

    def start_observation(self, **kwargs) -> "_StubObservation":
        child = _StubObservation()
        self.child_calls.append({"kwargs": kwargs, "handle": child})
        return child

    def end(self) -> None:
        self.ended = True


class _StubContextManager:
    """模拟 SDK 的 context manager：进入 yield handle，退出（end_on_exit=True）自动 end。"""

    def __init__(self, handle: _StubObservation) -> None:
        self.handle = handle

    def __enter__(self) -> _StubObservation:
        return self.handle

    def __exit__(self, *exc) -> bool:
        self.handle.end()
        return False


class _StubLangfuse:
    def __init__(self) -> None:
        self.seeds: list[str] = []
        self.root_calls: list[dict] = []
        self.root_handles: list[_StubObservation] = []
        self.flushed = 0

    def create_trace_id(self, *, seed: str | None = None) -> str:
        self.seeds.append(seed or "")
        return f"tid[{seed}]"

    def start_as_current_observation(self, **kwargs) -> _StubContextManager:
        handle = _StubObservation()
        self.root_calls.append(kwargs)
        self.root_handles.append(handle)
        return _StubContextManager(handle)

    def flush(self) -> None:
        self.flushed += 1


async def test_null_tracer_noop() -> None:
    """NullTracer 降级空实现（不抛、不做事）。"""
    await NullTracer().record(_trace())


async def test_langfuse_tracer_records_trace_and_generation() -> None:
    """上报形状：seed 映射 trace_id + 根 observation(name/input/output/metadata)
    + generation(usage 入 metadata) + 双 end；异常被兜底。"""
    stub = _StubLangfuse()
    tracer = LangfuseTracer(public_key="pk", secret_key="sk", host="http://x", client=stub)
    await tracer.record(_trace())
    assert stub.seeds == ["run-7"]
    assert len(stub.root_calls) == 1
    call = stub.root_calls[0]
    assert call["trace_context"] == {"trace_id": "tid[run-7]"}
    assert call["name"] == "pipeline.run"
    assert call["as_type"] == "span"
    assert call["input"] == "@QuantaBot hi"
    assert call["output"] == "replied: 链路完整"
    assert len(stub.root_handles) == 1
    root = stub.root_handles[0]
    assert root.ended, "root observation 应在退出 context manager 时 end"
    assert len(root.child_calls) == 1
    gen = root.child_calls[0]
    assert gen["kwargs"]["name"] == "generation"
    assert gen["kwargs"]["as_type"] == "generation"
    assert gen["kwargs"]["metadata"]["cost_li"] == 8
    assert gen["handle"].ended, "generation 应显式 end"


async def test_langfuse_tracer_skips_generation_when_not_generated() -> None:
    """未进生成阶段（skip/failed/rejected）→ 只上报根 observation 不上报 generation。"""
    stub = _StubLangfuse()
    tracer = LangfuseTracer("pk", "sk", "http://x", client=stub)
    await tracer.record(_trace(decision="skipped_idempotent", generated_content=None))
    assert stub.root_handles[0].child_calls == []


async def test_langfuse_tracer_swallows_client_errors() -> None:
    """观测边界：Langfuse 任何故障只 WARNING 不抛（不阻断业务链路）。"""

    class _Exploding:
        def start_as_current_observation(self, **kwargs):
            raise RuntimeError("langfuse down")

    tracer = LangfuseTracer("pk", "sk", "http://x", client=_Exploding())
    await tracer.record(_trace())  # 不抛即通过
