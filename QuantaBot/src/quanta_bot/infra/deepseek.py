"""infra/deepseek —— LLMClient 端口实现（DeepSeek OpenAI 兼容端点；httpx 直调不引 SDK）。

职责：DeepSeekClient 真客户端（/chat/completions，Pydantic 契约校验）+ FakeLLM（单测/降级）。
边界：不做重试/熔断（M5 熔断层）；超时档由 composition 从 Settings 注入；
      所有失败统一包 LLMClientError——管线的 failed 分支只认这一种类型。
"""

import httpx

from quanta_bot.pipeline.ports import LLMClientError, LLMResult


class FakeLLM:
    """内存 fake：默认固定文本；responses 剧本按调用序弹出（可控测试 LLM）。

    calls 记录每次调用参数（system/user/json_mode/max_tokens/temperature）——断言调用形状用。
    tokens 500/100 使成本断言可观测（estimate≈8 厘/次，沿用 M2 口径）。
    """

    def __init__(
        self,
        default_content: str = "收到你的 @ 啦，等你 @ 我的事我尽量接住～（M2 真链路测试回复）",
        responses: list[str] | None = None,
    ) -> None:
        self._default = default_content
        self._responses = list(responses) if responses is not None else None
        self.calls: list[dict[str, object]] = []

    async def complete(
        self,
        system: str,
        user: str,
        *,
        json_mode: bool = False,
        max_tokens: int | None = None,
        temperature: float | None = None,
    ) -> LLMResult:
        self.calls.append(
            {
                "system": system,
                "user": user,
                "json_mode": json_mode,
                "max_tokens": max_tokens,
                "temperature": temperature,
            }
        )
        content = self._responses.pop(0) if self._responses else self._default
        return LLMResult(
            content=content,
            prompt_tokens=500,
            completion_tokens=100,
            usage_complete=True,
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
        *,
        enable_thinking: bool | None = None,
    ) -> None:
        self._model = model
        self._enable_thinking = enable_thinking
        self._http = httpx.AsyncClient(
            base_url=base_url,
            timeout=timeout_seconds,
            headers={"Authorization": f"Bearer {api_key}"},
            transport=transport,
        )

    async def complete(
        self,
        system: str,
        user: str,
        *,
        json_mode: bool = False,
        max_tokens: int | None = None,
        temperature: float | None = None,
    ) -> LLMResult:
        """调 chat/completions 并解析（失败一律 LLMClientError）。"""
        payload: dict[str, object] = {
            "model": self._model,
            "messages": [
                {"role": "system", "content": system},
                {"role": "user", "content": user},
            ],
        }
        if json_mode:  # 轻量调用：结构化输出（DeepSeek OpenAI 兼容 response_format）
            payload["response_format"] = {"type": "json_object"}
        if max_tokens is not None:  # 轻量调用：输出上限（成本闸）
            payload["max_tokens"] = max_tokens
        if temperature is not None:  # 评测确定性；0.0 不能因真假值判断被丢弃
            payload["temperature"] = temperature
        if self._enable_thinking is not None:
            payload["enable_thinking"] = self._enable_thinking
        try:
            resp = await self._http.post("/chat/completions", json=payload)
            resp.raise_for_status()
        except httpx.HTTPError as exc:
            raise LLMClientError(f"DeepSeek 调用失败：{exc}") from exc
        try:
            # resp.json() 放在契约解析块：200 + 非 JSON 体（如网关 HTML）的
            # JSONDecodeError（ValueError 子类）也统一包装，不留逃逸域异常
            data = resp.json()
            content = data["choices"][0]["message"]["content"]
            usage = data.get("usage")
            usage_is_mapping = isinstance(usage, dict)
            usage = usage if usage_is_mapping else {}
            prompt_tokens = 0
            completion_tokens = 0
            prompt_tokens_valid = "prompt_tokens" in usage
            completion_tokens_valid = "completion_tokens" in usage
            if prompt_tokens_valid:
                try:
                    prompt_tokens = int(usage["prompt_tokens"])
                    prompt_tokens_valid = prompt_tokens >= 0
                except (TypeError, ValueError):
                    prompt_tokens_valid = False
                if not prompt_tokens_valid:
                    prompt_tokens = 0
            if completion_tokens_valid:
                try:
                    completion_tokens = int(usage["completion_tokens"])
                    completion_tokens_valid = completion_tokens >= 0
                except (TypeError, ValueError):
                    completion_tokens_valid = False
                if not completion_tokens_valid:
                    completion_tokens = 0
            return LLMResult(
                content=content,
                prompt_tokens=prompt_tokens,
                completion_tokens=completion_tokens,
                usage_complete=(
                    usage_is_mapping and prompt_tokens_valid and completion_tokens_valid
                ),
            )
        except (KeyError, IndexError, TypeError, ValueError) as exc:
            raise LLMClientError(f"DeepSeek 响应契约不符：{exc}") from exc

    async def aclose(self) -> None:
        """释放连接（composition 收尾统一调用）。"""
        await self._http.aclose()
