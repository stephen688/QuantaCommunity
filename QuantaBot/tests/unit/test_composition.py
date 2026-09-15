"""装配根行为测试：fake_mode 组装 + 真实 audit 注入 + 管线跑通。"""

import pytest

from quanta_bot.composition import build_pipeline_deps
from quanta_bot.crosscutting.killswitch import ControlPlane
from quanta_bot.infra.audit_db import SQLiteAudit
from quanta_bot.infra.deepseek import FakeLLM
from quanta_bot.infra.kv import InMemoryKV
from quanta_bot.infra.main_service import FakeReplyWriter
from quanta_bot.infra.settings import Settings
from quanta_bot.infra.tracing import NullTracer
from quanta_bot.pipeline.pipeline import PipelineDeps, run
from quanta_bot.pipeline.trigger import TriggerEvent


async def test_fake_mode_deps_and_pipeline_run(tmp_path) -> None:
    """fake_mode=True → kv/写库为 fake、audit 为真 SQLite；管线用装配结果跑通一次。"""
    deps = build_pipeline_deps(
        Settings(_env_file=None, fake_mode=True, audit_db_path=str(tmp_path / "d.db"))
    )
    assert isinstance(deps, PipelineDeps)
    assert isinstance(deps.kv, InMemoryKV)
    assert isinstance(deps.reply_writer, FakeReplyWriter)
    assert isinstance(deps.llm, FakeLLM)
    assert isinstance(deps.audit, SQLiteAudit)
    assert isinstance(deps.tracer, NullTracer)
    assert isinstance(deps.control_plane, ControlPlane)
    result = await run(
        TriggerEvent(comment_id=1, post_id=2, author_user_id=3, content="@QuantaBot hi"), deps
    )
    assert result == "replied"


async def test_non_fake_mode_raises_honestly(tmp_path) -> None:
    """fake_mode=False 在 M2 前显式炸（不静默假装可用——诚实口径）。"""
    with pytest.raises(NotImplementedError):
        build_pipeline_deps(
            Settings(_env_file=None, fake_mode=False, audit_db_path=str(tmp_path / "d.db"))
        )
