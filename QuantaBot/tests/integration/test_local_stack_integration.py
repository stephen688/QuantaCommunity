"""本地 compose 栈连通 smoke：MQ（P0-1 探测）+ Qdrant readyz。"""

import httpx

from quanta_bot.infra import mq as infra_mq
from quanta_bot.infra.settings import Settings


async def test_mq_reachable() -> None:
    s = Settings()
    assert s.mq_url, "integration 需要 .env 配置 QUANTABOT_MQ_URL"
    assert await infra_mq.check_connection(s.mq_url, timeout_seconds=5.0) is True


async def test_qdrant_ready() -> None:
    s = Settings()
    assert s.qdrant_url, "integration 需要 .env 配置 QUANTABOT_QDRANT_URL"
    async with httpx.AsyncClient(timeout=5.0) as client:
        resp = await client.get(f"{s.qdrant_url}/readyz")
        assert resp.status_code == 200
