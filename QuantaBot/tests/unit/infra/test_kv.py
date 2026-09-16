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


async def test_fake_kv_get_set_roundtrip() -> None:
    """get/set：可读回写入值；TTL 过期后读 None。"""
    kv = InMemoryKV()
    await kv.set("switch:kill", "true", ttl_seconds=60)
    assert await kv.get("switch:kill") == "true"
    await kv.set("switch:kill", "false", ttl_seconds=0)  # ttl=0 立即过期
    assert await kv.get("switch:kill") is None
    assert await kv.get("absent") is None


async def test_fake_kv_increment_accumulates_with_ttl() -> None:
    """increment：累加返回新值（Redis INCR+EXPIRE 语义）；不存在的键从 0 起算。"""
    kv = InMemoryKV()
    assert await kv.increment("cost:20260914", 8, ttl_seconds=60) == 8
    assert await kv.increment("cost:20260914", 8, ttl_seconds=60) == 16
    assert await kv.get("cost:20260914") == "16"


async def test_fake_kv_set_if_absent_records_value() -> None:
    """幂等键 set_if_absent 后 get 可见（键存在性可查询）。"""
    kv = InMemoryKV()
    assert await kv.set_if_absent("idem:1", ttl_seconds=60) is True
    assert await kv.get("idem:1") is not None


async def test_redis_kv_constructs_without_connection() -> None:
    """RedisKV 构造不建连接（redis.from_url 惰性连接）——composition 真模式装配可测。"""
    from quanta_bot.infra.kv import RedisKV

    kv = RedisKV("redis://127.0.0.1:6379/0", timeout_seconds=2.0)
    assert kv is not None
