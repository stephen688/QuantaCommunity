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


async def test_deepseek_client_non_json_200_wrapped() -> None:
    """200 + 非 JSON 体（如网关 HTML 页）→ JSONDecodeError 也统一包 LLMClientError，
    不逃逸出域异常（否则管线 failed 分支无法归因、Langfuse 失败轨迹缺失）。"""
    transport = httpx.MockTransport(
        lambda request: httpx.Response(200, text="<html>gateway error page</html>")
    )
    client = DeepSeekClient("https://api.deepseek.com", "sk-test", "deepseek-chat", 5.0, transport)
    with pytest.raises(LLMClientError):
        await client.complete(system="s", user="u")


async def test_deepseek_json_mode_and_max_tokens_in_payload() -> None:
    """轻量调用载体：json_mode → response_format；max_tokens 透传；Bearer 鉴权头不变。"""
    captured: dict[str, object] = {}

    def handler(request: httpx.Request) -> httpx.Response:
        captured["payload"] = json.loads(request.content.decode("utf-8"))
        captured["authorization"] = request.headers["Authorization"]
        return httpx.Response(200, json={"choices": [{"message": {"content": "{}"}}], "usage": {}})

    client = DeepSeekClient(
        "https://api.test", "sk-x", "deepseek-chat", 5.0, transport=httpx.MockTransport(handler)
    )
    await client.complete("s", "u", json_mode=True, max_tokens=400)
    payload = captured["payload"]
    assert payload["response_format"] == {"type": "json_object"}
    assert payload["max_tokens"] == 400
    assert captured["authorization"] == "Bearer sk-x"
    await client.aclose()


async def test_fake_llm_scripted_responses_and_call_log() -> None:
    """FakeLLM 剧本模式：按序弹出内容并记录调用参数（eval/管线测试的可控 LLM）。"""
    fake = FakeLLM(responses=['{"should_reply": true}', "第二段"])
    first = await fake.complete("s", "u", json_mode=True, max_tokens=300)
    second = await fake.complete("s", "u2")
    assert first.content == '{"should_reply": true}'
    assert second.content == "第二段"
    assert fake.calls[0]["json_mode"] is True and fake.calls[0]["max_tokens"] == 300
    assert fake.calls[1]["json_mode"] is False
