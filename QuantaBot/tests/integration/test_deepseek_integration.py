"""DeepSeek 真调用 smoke：网络/鉴权/契约/usage 全链路一次。"""

from quanta_bot.infra.deepseek import DeepSeekClient
from quanta_bot.infra.settings import Settings


async def test_real_deepseek_complete() -> None:
    s = Settings()  # 读本地 .env（锚定项目根，见 Task 1）
    assert s.deepseek_api_key, "integration 需要 .env 配置 QUANTABOT_DEEPSEEK_API_KEY"
    client = DeepSeekClient(
        s.deepseek_base_url, s.deepseek_api_key, s.deepseek_model, s.llm_timeout_seconds
    )
    try:
        result = await client.complete(system="你是测试助手。", user="只回复两个字：收到")
        assert result.content.strip()
        assert result.prompt_tokens > 0
        assert result.completion_tokens > 0
    finally:
        await client.aclose()
