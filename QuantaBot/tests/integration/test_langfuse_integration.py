"""Langfuse 真上报 smoke：record+flush 后经公共 API 查回 trace（网页可见轨迹的自动化等价）。"""

import asyncio
import time

import httpx

from quanta_bot.infra.settings import Settings
from quanta_bot.infra.tracing import LangfuseTracer
from quanta_bot.pipeline.ports import RunTrace


async def test_langfuse_tracer_roundtrip() -> None:
    s = Settings()
    assert s.langfuse_public_key and s.langfuse_secret_key, (
        "integration 需在 .env 配 Langfuse 项目密钥"
    )
    comment_id = int(time.time()) % 1_000_000_000  # 唯一 id 防串扰
    tracer = LangfuseTracer(s.langfuse_public_key, s.langfuse_secret_key, s.langfuse_host)
    await tracer.record(
        RunTrace(
            comment_id=comment_id,
            post_id=99,
            trigger_content="@QuantaBot 集成测试",
            decision="replied",
            mode="生活玩梗",
            reason="integration smoke",
            generated_content="[QuantaBot·AI 学长] 集成测试回复",
            prompt_tokens=500,
            completion_tokens=100,
            cost_li=8,
            daily_cost_li_after=16,
        )
    )
    tracer.flush()
    # 上报异步落库（worker→clickhouse），轮询等它出现（≤10s）
    found = False
    async with httpx.AsyncClient(
        base_url=s.langfuse_host,
        auth=(s.langfuse_public_key, s.langfuse_secret_key),
        timeout=s.langfuse_timeout_seconds,
    ) as http:
        for _ in range(10):
            resp = await http.get("/api/public/traces", params={"page": 1})
            assert resp.status_code == 200
            ids = [t.get("id") for t in resp.json().get("data", [])]
            if f"run-{comment_id}" in ids:
                found = True
                break
            await asyncio.sleep(1)
    assert found, "Langfuse 网页应能查到 run-<comment_id> 轨迹（10s 内）"
