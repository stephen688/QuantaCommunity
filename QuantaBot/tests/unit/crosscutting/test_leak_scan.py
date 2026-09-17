"""泄漏扫描测试：五类判据命中替换 [已脱敏]，绝不阻断（防误判=整轮失败）。"""

from quanta_bot.crosscutting.leak_scan import sanitize


def test_sanitize_replaces_all_five_categories() -> None:
    content = (
        "我的 key 是 sk-abcdef1234567890abcd，Bearer eyJhbGciOiJIUzI1NiJ9，"
        "地址 http://127.0.0.1:8080/bot，键 quantabot:switch:kill，工具是 Langfuse，"
        "我是 DeepSeek 训练的，服务 https://main.demo0.internal/api"
    )
    result = sanitize(
        content,
        extra_patterns=(("main_service", r"https://main\.demo0\.internal[^\s]*"),),
    )
    assert result.content.count("[已脱敏]") == 7  # 五类 + Bearer + extra
    assert "sk-" not in result.content and "quantabot:" not in result.content
    assert len(result.hits) == 7  # trace 留痕


def test_sanitize_keeps_normal_reply_untouched() -> None:
    """正常回复零误伤（判据收窄：只扫内部形态，不扫正常措辞）。"""
    result = sanitize("[QuantaBot·AI 学长] 抱抱，今晚先拆个小计划。")
    assert result.content == "[QuantaBot·AI 学长] 抱抱，今晚先拆个小计划。" and result.hits == ()
