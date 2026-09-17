"""infra/embedding —— EmbeddingClient 端口真实现（Qwen/DashScope OpenAI 兼容端点，httpx 直调）。

职责：批量文本转向量（记忆写入/召回与 RAG 摄取/检索共用；1024 维与主服务同源——技术选型 §6.6）。
边界：不做重试/熔断（M5）；[联调校准点] base_url/model 以 demo0 QwenEmbeddingConfig 为准对齐；
      失败统一 EmbeddingError——调用方（composition/摄取）按降级路径处理，不阻断回复链路。
实现：结构化满足 memory.ports.EmbeddingClient 协议（embed 签名一致，composition 注入点替换）。
"""

import httpx


class EmbeddingError(Exception):
    """embedding 调用失败（网络/HTTP/契约不符统一包装）。"""


class QwenEmbeddingClient:
    """Qwen embedding 真客户端（OpenAI 兼容 /embeddings；transport 供 MockTransport 单测注入）。"""

    def __init__(
        self,
        base_url: str,
        api_key: str,
        model: str,
        timeout_seconds: float,
        transport: httpx.AsyncBaseTransport | None = None,
    ) -> None:
        self._model = model
        self._http = httpx.AsyncClient(
            base_url=base_url,
            timeout=timeout_seconds,
            headers={"Authorization": f"Bearer {api_key}"},
            transport=transport,
        )

    async def embed(self, texts: list[str]) -> list[list[float]]:
        if not texts:
            return []
        payload: dict[str, object] = {"model": self._model, "input": texts}
        try:
            resp = await self._http.post("/embeddings", json=payload)
            resp.raise_for_status()
        except httpx.HTTPError as exc:
            raise EmbeddingError(f"embedding 调用失败：{exc}") from exc
        try:
            data = resp.json()["data"]
            return [list(map(float, item["embedding"])) for item in data]
        except (KeyError, TypeError, ValueError) as exc:
            raise EmbeddingError(f"embedding 响应契约不符：{exc}") from exc

    async def aclose(self) -> None:
        await self._http.aclose()
