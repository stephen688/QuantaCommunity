"""infra/deepseek —— LLMClient 端口实现（DeepSeek OpenAI 兼容端点；httpx 直调不引 SDK）。

职责：DeepSeekClient 真客户端（/chat/completions，Pydantic 契约校验）+ FakeLLM（单测/降级）。
边界：不做重试/熔断（M5 熔断层）；超时档由 composition 从 Settings 注入；
      所有失败统一包 LLMClientError——管线的 failed 分支只认这一种类型。
"""

import httpx

from quanta_bot.pipeline.ports import LLMClientError, LLMResult


class FakeLLM:
    """内存 fake（固定文本；tokens 500/100 使成本断言可观测——estimate≈8 厘/次）。"""

    async def complete(self, system: str, user: str) -> LLMResult:
        return LLMResult(
            content="收到你的 @ 啦，等你 @ 我的事我尽量接住～（M2 真链路测试回复）",
            prompt_tokens=500,
            completion_tokens=100,
        )


class DeepSeekClient:
    """DeepSeek 真客户端（OpenAI 兼容；transport 参数供 MockTransport 单测注入）。"""

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

    async def complete(self, system: str, user: str) -> LLMResult:
        """调 chat/completions 并解析（失败一律 LLMClientError）。"""
        payload = {
            "model": self._model,
            "messages": [
                {"role": "system", "content": system},
                {"role": "user", "content": user},
            ],
        }
        try:
            resp = await self._http.post("/chat/completions", json=payload)
            resp.raise_for_status()
            data = resp.json()
        except httpx.HTTPError as exc:
            raise LLMClientError(f"DeepSeek 调用失败：{exc}") from exc
        try:
            content = data["choices"][0]["message"]["content"]
            usage = data.get("usage") or {}
            return LLMResult(
                content=content,
                prompt_tokens=int(usage.get("prompt_tokens", 0)),
                completion_tokens=int(usage.get("completion_tokens", 0)),
            )
        except (KeyError, IndexError, TypeError, ValueError) as exc:
            raise LLMClientError(f"DeepSeek 响应契约不符：{exc}") from exc

    async def aclose(self) -> None:
        """释放连接（composition 收尾统一调用）。"""
        await self._http.aclose()
