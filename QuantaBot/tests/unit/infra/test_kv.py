"""InMemoryKV（fake Redis）的行为测试：SETNX 语义 + TTL 过期。"""

from quanta_bot.infra.kv import InMemoryKV


async def test_set_if_absent_setnx_semantics() -> None:
    """同键首次 True、未过期重复 False（幂等的物理基础）。"""
    kv = InMemoryKV()
    assert await kv.set_if_absent("k", ttl_seconds=60) is True
    assert await kv.set_if_absent("k", ttl_seconds=60) is False


async def test_set_if_absent_ttl_expiry() -> None:
    """TTL 过期后可再次设置（模拟重投窗口过期）。"""
    kv = InMemoryKV()
    assert await kv.set_if_absent("k", ttl_seconds=0) is True
    assert await kv.set_if_absent("k", ttl_seconds=60) is True  # ttl=0 立即过期
