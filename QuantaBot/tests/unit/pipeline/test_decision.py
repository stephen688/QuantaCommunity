"""decision 最小版（M1 固定决策）的行为测试。"""

from quanta_bot.pipeline.decision import DecisionResult, decide
from quanta_bot.pipeline.trigger import TriggerEvent


def _event() -> TriggerEvent:
    return TriggerEvent(comment_id=1, post_id=2, author_user_id=3, content="@QuantaBot hi")


def test_decide_always_replies_in_m1() -> None:
    """M1 骨架固定决策：值得回 + 模式合法 + 理由可追溯。"""
    d = decide(_event())
    assert isinstance(d, DecisionResult)
    assert d.should_reply is True
    assert d.mode in ("专业答疑", "生活玩梗", "情绪陪伴", "治理")
    assert d.reason
