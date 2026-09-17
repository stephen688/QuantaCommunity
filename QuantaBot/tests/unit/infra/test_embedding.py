"""Qwen embedding 客户端测试（MockTransport 隔离——请求形状与响应解析，不发真请求）。"""

import json

import httpx

from quanta_bot.infra.embedding import EmbeddingError, QwenEmbeddingClient


async def test_embed_posts_openai_compatible_payload() -> None:
    captured: dict[str, object] = {}

    def handler(request: httpx.Request) -> httpx.Response:
        captured["payload"] = json.loads(request.content.decode("utf-8"))
        captured["auth"] = request.headers.get("Authorization")
        return httpx.Response(
            200, json={"data": [{"embedding": [0.1, 0.2]}, {"embedding": [0.3, 0.4]}]}
        )

    client = QwenEmbeddingClient(
        "https://emb.test", "sk-x", "text-embedding-v4", 5.0, transport=httpx.MockTransport(handler)
    )
    vectors = await client.embed(["你好", "世界"])
    assert captured["payload"] == {"model": "text-embedding-v4", "input": ["你好", "世界"]}
    assert captured["auth"] == "Bearer sk-x"
    assert vectors == [[0.1, 0.2], [0.3, 0.4]]  # 顺序对应
    await client.aclose()


async def test_embed_empty_input_returns_empty_without_call() -> None:
    """空入参直接返回空列表（零网络调用——批量召回空候选的常规路径）。"""
    calls: list[httpx.Request] = []

    def handler(request: httpx.Request) -> httpx.Response:
        calls.append(request)
        return httpx.Response(200, json={"data": []})

    client = QwenEmbeddingClient(
        "https://emb.test", "sk-x", "m", 5.0, transport=httpx.MockTransport(handler)
    )
    assert await client.embed([]) == []
    assert not calls  # 未发请求
    await client.aclose()


async def test_embed_wraps_contract_violations() -> None:
    """非 JSON/缺 data 字段统一 EmbeddingError（不留逃逸域异常）。"""
    client = QwenEmbeddingClient(
        "https://emb.test",
        "sk-x",
        "m",
        5.0,
        transport=httpx.MockTransport(lambda r: httpx.Response(200, json={"unexpected": 1})),
    )
    try:
        await client.embed(["x"])
        raise AssertionError("应当抛 EmbeddingError")
    except EmbeddingError:
        pass


async def test_embed_wraps_http_errors() -> None:
    """HTTP 层失败（超时/5xx）→ EmbeddingError（调用方降级，不阻断回复链路）。"""
    client = QwenEmbeddingClient(
        "https://emb.test",
        "sk-x",
        "m",
        5.0,
        transport=httpx.MockTransport(lambda r: httpx.Response(500)),
    )
    try:
        await client.embed(["x"])
        raise AssertionError("应当抛 EmbeddingError")
    except EmbeddingError:
        pass
