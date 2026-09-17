"""装配根行为测试：fake 全内存；真模式逐项真接/缺配置降级（不发网络）。"""

from quanta_bot.composition import build_runtime
from quanta_bot.crosscutting.killswitch import ControlPlane
from quanta_bot.infra.audit_db import SQLiteAudit
from quanta_bot.infra.deepseek import DeepSeekClient, FakeLLM
from quanta_bot.infra.kv import InMemoryKV, RedisKV
from quanta_bot.infra.main_service import (
    FakeCommentTreeFetcher,
    FakeReplyWriter,
    HTTPCommentTreeFetcher,
    HTTPReplyWriter,
    UnimplementedReplyWriter,
)
from quanta_bot.infra.settings import Settings
from quanta_bot.infra.tracing import LangfuseTracer, NullTracer
from quanta_bot.pipeline.pipeline import run
from quanta_bot.pipeline.trigger import TriggerEvent

# 合法决策 JSON 剧本（决策层 v2 真实化后，replied 路径首位 LLM 响应必须是它；
# 装配内置 FakeLLM 为固定文本过不了决策 JSON 解析，跑通验证时替换为同类型剧本版）
_DECISION_JSON = (
    '{"should_reply": true, "mode": "生活玩梗", "confidence": 0.9, "reason": "真诚求助"}'
)


def _settings(tmp_path, **overrides) -> Settings:
    base = dict(
        _env_file=None,
        fake_mode=True,
        audit_db_path=str(tmp_path / "d.db"),
    )
    base.update(overrides)
    return Settings(**base)


async def test_fake_mode_deps_and_pipeline_run(tmp_path) -> None:
    """fake_mode=True → 全内存依赖；管线用装配结果跑通一次。"""
    runtime = build_runtime(_settings(tmp_path))
    deps = runtime.deps
    assert isinstance(deps.kv, InMemoryKV)
    assert isinstance(deps.reply_writer, FakeReplyWriter)
    assert isinstance(deps.llm, FakeLLM)
    assert isinstance(deps.tracer, NullTracer)
    assert isinstance(deps.comment_tree, FakeCommentTreeFetcher)
    assert isinstance(deps.control_plane, ControlPlane)
    assert isinstance(deps.audit, SQLiteAudit)
    # 决策层 v2 真实化：默认 FakeLLM 固定文本过不了决策 JSON 解析，替换为同类型剧本版跑通 replied
    deps.llm = FakeLLM(responses=[_DECISION_JSON, "fake 模式端到端回复"])
    result = await run(
        TriggerEvent(
            event_id="evt-1",
            comment_id=1,
            post_id=2,
            commenter_user_id=3,
            content="@QuantaBot 帮我选课",
            mentioned_bot=True,
        ),
        deps,
    )
    assert result == "replied"


async def test_real_mode_degrades_gracefully_when_unconfigured(tmp_path) -> None:
    """真模式缺全部配置 → 不炸、逐项降级（kv 内存/LLM fake/tracer null），写库为诚实占位。"""
    runtime = build_runtime(_settings(tmp_path, fake_mode=False))
    deps = runtime.deps
    assert isinstance(deps.kv, InMemoryKV)  # 降级（warning 留痕）
    assert isinstance(deps.llm, FakeLLM)  # 降级
    assert isinstance(deps.tracer, NullTracer)  # 降级
    assert isinstance(deps.reply_writer, UnimplementedReplyWriter)  # 不假装：P0-5 未接入
    assert isinstance(deps.comment_tree, FakeCommentTreeFetcher)  # P0-2 未接入


async def test_real_mode_builds_real_clients_when_configured(tmp_path) -> None:
    """真模式配置齐 → 逐项建真客户端（构造不建连，无网络）。"""
    runtime = build_runtime(
        _settings(
            tmp_path,
            fake_mode=False,
            redis_url="redis://127.0.0.1:6379/0",
            deepseek_api_key="sk-test",
            langfuse_public_key="pk-test",
            langfuse_secret_key="sk-test",
            main_service_base_url="http://demo0.test",
            main_service_token="svc-token",
        )
    )
    try:
        deps = runtime.deps
        assert isinstance(deps.kv, RedisKV)
        assert isinstance(deps.llm, DeepSeekClient)
        assert isinstance(deps.tracer, LangfuseTracer)
        assert isinstance(
            deps.reply_writer, HTTPReplyWriter
        )  # C-5 真写库接入（与评论树共享 client）
        assert isinstance(deps.comment_tree, HTTPCommentTreeFetcher)  # C-2 真客户端接入
    finally:
        await runtime.aclose()


async def test_real_mode_without_main_service_fails_honestly(tmp_path) -> None:
    """真模式跑管线 → 写库占位抛错 → failed（绝不静默假装写库成功，红线 §0.5 精神）。"""
    runtime = build_runtime(_settings(tmp_path, fake_mode=False))
    # LLM 已降级为 FakeLLM；替换为剧本版让链路走到写库占位（failed 必须来自写库，而非决策层）
    runtime.deps.llm = FakeLLM(responses=[_DECISION_JSON, "写库占位前的生成回复"])
    result = await run(
        TriggerEvent(
            event_id="evt-2",
            comment_id=2,
            post_id=3,
            commenter_user_id=4,
            content="@QuantaBot 帮我看看这道题",
            mentioned_bot=True,
        ),
        runtime.deps,
    )
    assert result == "failed"
    await runtime.aclose()


async def test_runtime_aclose_is_idempotent(tmp_path) -> None:
    """aclose 幂等（lifespan 异常路径安全收尾）。"""
    runtime = build_runtime(_settings(tmp_path, fake_mode=False))
    await runtime.aclose()
    await runtime.aclose()  # 不抛即通过
