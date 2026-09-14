"""generation 最小版（M1 固定回复）的行为测试。"""

from quanta_bot.pipeline.generation import generate
from quanta_bot.pipeline.trigger import TriggerEvent


def test_generate_fixed_reply_with_ai_badge() -> None:
    """固定回复必须带 AI 身份标识（红线 §0.1）且回复锚点正确。"""
    ev = TriggerEvent(comment_id=11, post_id=22, author_user_id=3, content="@QuantaBot hi")
    reply = generate(ev, decision=None)  # M1 固定文本不读 decision（签名允许 None）
    assert reply.reply_to_comment_id == 11
    assert reply.post_id == 22
    assert "QuantaBot" in reply.content and "AI" in reply.content
