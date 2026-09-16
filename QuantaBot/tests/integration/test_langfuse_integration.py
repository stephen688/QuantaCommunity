"""Langfuse 真上报 smoke：record+flush 后经公共 API 查回（网页可见轨迹的自动化等价）。

Langfuse v4（events_only）已移除 GET /api/public/traces —— 正确查法是 v2 Observations
API：trace 即共享同一 traceId 的 observations 行集合。断言三件事：根 observation
（pipeline.run）在、generation 行在、generation 的 token metadata 实质落库。
"""

import asyncio
import time

import httpx
from langfuse import Langfuse

from quanta_bot.infra.settings import Settings
from quanta_bot.infra.tracing import LangfuseTracer
from quanta_bot.pipeline.ports import RunTrace


async def test_langfuse_tracer_roundtrip() -> None:
    s = Settings()
    assert s.langfuse_public_key and s.langfuse_secret_key, (
        "integration 需在 .env 配 Langfuse 项目密钥"
    )
    comment_id = int(time.time()) % 1_000_000_000  # 唯一 id 防串扰
    trace_id = Langfuse.create_trace_id(seed=f"run-{comment_id}")  # 与实现同源映射
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
            resp = await http.get(
                "/api/public/v2/observations",
                params={"traceId": trace_id, "fields": "core,basic,metadata"},
            )
            assert resp.status_code == 200
            rows = resp.json().get("data", [])
            names = {r.get("name") for r in rows}
            generations = [r for r in rows if r.get("name") == "generation"]
            metadata_ok = any(
                (r.get("metadata") or {}).get("prompt_tokens") == 500 for r in generations
            )
            if "pipeline.run" in names and "generation" in names and metadata_ok:
                found = True
                break
            await asyncio.sleep(1)
    assert found, f"Langfuse 应能查到 traceId={trace_id} 的根+generation（10s 内）"
