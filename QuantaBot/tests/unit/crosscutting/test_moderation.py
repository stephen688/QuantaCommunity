"""规则预检（审核第一道）的行为测试。"""

from quanta_bot.crosscutting.moderation import precheck


async def test_normal_content_passes() -> None:
    """正常文本通过。"""
    v = await precheck("@QuantaBot 帮我看看选课")
    assert v.passed is True


async def test_sensitive_word_blocked() -> None:
    """命中占位敏感词 → 拦截（静默优于乱回：不通过 = 不回）。"""
    v = await precheck("@QuantaBot 这条包含测试敏感词")
    assert v.passed is False
    assert "敏感词" in v.reason
