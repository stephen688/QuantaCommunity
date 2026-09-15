"""context 异步组装行为测试（fake 评论树）。"""

from quanta_bot.infra.main_service import FakeCommentTreeFetcher
from quanta_bot.pipeline.context import build_context
from quanta_bot.pipeline.trigger import TriggerEvent


def _event() -> TriggerEvent:
    return TriggerEvent(
        event_id="evt-11",
        comment_id=11,
        post_id=22,
        commenter_user_id=5,
        content="@QuantaBot hi",
        mentioned_bot=True,
    )


async def test_build_context_renders_thread() -> None:
    """渲染主楼 + 评论（含父链与 AI 标记）——最小可读文本，M3 再做筛选与长度预算。"""
    text = await build_context(_event(), FakeCommentTreeFetcher())
    assert "主楼" in text and "占位" in text
    assert "评论#" in text


async def test_build_context_fake_thread_matches_post() -> None:
    """fake fetcher 返回与 post_id 同帖线程（隔离锚点正确）。"""
    thread = await FakeCommentTreeFetcher().fetch(22)
    assert thread.post.post_id == 22
    assert all(isinstance(c.is_ai, bool) for c in thread.comments)
