"""trigger 契约模型与 @ 检测的行为测试。"""

from quanta_bot.pipeline.trigger import AI_NICKNAME, TriggerEvent, detect_mention


def test_detect_mention_hit_and_miss() -> None:
    """@昵称命中（大小写不敏感）；无 @ 或 @ 他人不命中。"""
    assert detect_mention("@QuantaBot 帮我看看选课") is True
    assert detect_mention("@quantabot 在吗") is True
    assert detect_mention("随便聊聊") is False
    assert detect_mention("@别人 你说说") is False


def test_trigger_event_fields() -> None:
    """事件契约字段完整（[Phase 0 对齐点] 内部最小集）。"""
    ev = TriggerEvent(comment_id=1, post_id=2, author_user_id=3, content="@QuantaBot hi")
    assert (ev.comment_id, ev.post_id, ev.author_user_id, ev.content) == (1, 2, 3, "@QuantaBot hi")
    assert AI_NICKNAME == "QuantaBot"
