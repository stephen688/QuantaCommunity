"""DeepSeek 客户端行为测试（httpx MockTransport 隔离网络——测请求形状与响应解析，不发真请求）。"""

import json

import httpx
import pytest

from quanta_bot.infra.deepseek import DeepSeekClient, FakeLLM
from quanta_bot.pipeline.ports import LLMClientError, LLMResult


async def test_fake_llm_returns_fixed_result() -> None:
    """FakeLLM：固定文本 + 确定用量（500/100——成本断言锚点）。"""
    result = await FakeLLM().complete(system="s", user="u")
    assert result.content
    assert result.prompt_tokens == 500
    assert result.completion_tokens == 100


def _ok_handler(requests_seen: list[httpx.Request]) -> httpx.MockTransport:
    def handler(request: httpx.Request) -> httpx.Response:
        requests_seen.append(request)
        return httpx.Response(
            200,
            json={
                "choices": [{"message": {"role": "assistant", "content": "收到"}}],
                "usage": {"prompt_tokens": 5, "completion_tokens": 7},
            },
        )

    return httpx.MockTransport(handler)


async def test_deepseek_client_request_shape_and_parse() -> None:
    """请求形状（URL/鉴权/模型/双消息）与响应解析（content+usage）。"""
    seen: list[httpx.Request] = []
    client = DeepSeekClient(
        base_url="https://api.deepseek.com",
        api_key="sk-test",
        model="deepseek-chat",
        timeout_seconds=5.0,
        transport=_ok_handler(seen),
    )
    try:
        result = await client.complete(system="你是测试助手", user="回复两个字")
    finally:
        await client.aclose()
    assert result == LLMResult(content="收到", prompt_tokens=5, completion_tokens=7)
    assert len(seen) == 1
    req = seen[0]
    assert req.url.path == "/chat/completions"
    assert req.headers["Authorization"] == "Bearer sk-test"
    body = json.loads(req.content)
    assert body["model"] == "deepseek-chat"
    assert [m["role"] for m in body["messages"]] == ["system", "user"]
    assert body["messages"][0]["content"] == "你是测试助手"


async def test_deepseek_client_http_error_wrapped() -> None:
    """HTTP 错误（4xx/5xx/网络）统一包成 LLMClientError（管线 failed 分支只认这个类型）。"""
    transport = httpx.MockTransport(lambda request: httpx.Response(500, json={"error": "boom"}))
    client = DeepSeekClient("https://api.deepseek.com", "sk-test", "deepseek-chat", 5.0, transport)
    with pytest.raises(LLMClientError):
        await client.complete(system="s", user="u")


async def test_deepseek_client_bad_contract_wrapped() -> None:
    """响应契约不符（缺 choices）→ LLMClientError（不吞成假成功——诚实口径）。"""
    transport = httpx.MockTransport(lambda request: httpx.Response(200, json={"unexpected": 1}))
    client = DeepSeekClient("https://api.deepseek.com", "sk-test", "deepseek-chat", 5.0, transport)
    with pytest.raises(LLMClientError):
        await client.complete(system="s", user="u")
