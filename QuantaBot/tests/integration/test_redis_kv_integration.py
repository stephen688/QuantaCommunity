"""真 Redis smoke：四语义 + 控制面 kill 生效（G5 感知链路）。"""

from uuid import uuid4

from quanta_bot.crosscutting.killswitch import SWITCH_KILL_KEY, ControlPlane
from quanta_bot.infra.kv import RedisKV
from quanta_bot.infra.settings import Settings


async def test_redis_kv_roundtrip() -> None:
    s = Settings()
    assert s.redis_url, "integration 需要 .env 配置 QUANTABOT_REDIS_URL"
    # 键带每次运行唯一后缀：set_if_absent/increment 首调断言依赖键不存在，
    # TTL 内重复跑套件时固定键会残留而误报（TTL 过期后自然回收，无需清理）
    run = uuid4().hex[:8]
    kv = RedisKV(s.redis_url, s.redis_timeout_seconds)
    try:
        assert await kv.ping() is True
        assert await kv.set_if_absent(f"quantabot:inttest:nx:{run}", ttl_seconds=60) is True
        assert await kv.set_if_absent(f"quantabot:inttest:nx:{run}", ttl_seconds=60) is False
        await kv.set(f"quantabot:inttest:k:{run}", "v", ttl_seconds=60)
        assert await kv.get(f"quantabot:inttest:k:{run}") == "v"
        assert await kv.increment(f"quantabot:inttest:cnt:{run}", 8, ttl_seconds=60) == 8
        assert await kv.increment(f"quantabot:inttest:cnt:{run}", 8, ttl_seconds=60) == 16
    finally:
        await kv.aclose()


async def test_control_plane_reads_real_redis() -> None:
    """kill 键写入真 Redis → ControlPlane.refresh 感知（≤5s 轮询的手动等价）。"""
    s = Settings()
    kv = RedisKV(s.redis_url, s.redis_timeout_seconds)
    try:
        await kv.set(SWITCH_KILL_KEY, "true", ttl_seconds=60)
        cp = ControlPlane(kv, poll_seconds=s.control_plane_poll_seconds)
        await cp.refresh()
        assert cp.snapshot.kill is True
        await kv.set(SWITCH_KILL_KEY, "false", ttl_seconds=60)
        await cp.refresh()
        assert cp.snapshot.kill is False
    finally:
        await kv.aclose()
