"""infra/kv —— KeyValueStore 端口实现（fake：内存版；真：redis-py asyncio）。

职责：提供 SETNX+TTL / get / set / INCR+EXPIRE 四种语义的两种实现，composition 按配置装配。
边界：fake 不持久化（重启即失——fake 定位；过期键惰性判断不主动清理，长跑内存缓增可接受）；
      RedisKV 为薄封装（redis-py 直调，无重试/熔断——M5 熔断层统一落地）。
已知坑：redis-py 的 set(nx=True, ex=) 返回 True/None（不是 False）；incrby 返回 int。
"""

import time

import redis.asyncio as aioredis


class InMemoryKV:
    """内存 fake（值表 + 过期时刻表；monotonic 时钟避免系统时间回拨干扰）。"""

    def __init__(self) -> None:
        self._values: dict[str, str] = {}
        self._expiry: dict[str, float] = {}

    def _alive(self, key: str, now: float) -> bool:
        expire_at = self._expiry.get(key)
        return expire_at is not None and expire_at > now

    async def set_if_absent(self, key: str, ttl_seconds: int) -> bool:
        """SETNX+TTL：键不存在或已过期 → 设置并 True；存在且未过期 → False。"""
        now = time.monotonic()
        if self._alive(key, now):
            return False
        self._values[key] = "1"
        self._expiry[key] = now + max(ttl_seconds, 0)
        return True

    async def get(self, key: str) -> str | None:
        """读键值；过期视为不存在。"""
        if self._alive(key, time.monotonic()):
            return self._values.get(key)
        return None

    async def set(self, key: str, value: str, ttl_seconds: int) -> None:
        """覆盖写 + TTL。"""
        now = time.monotonic()
        self._values[key] = value
        self._expiry[key] = now + max(ttl_seconds, 0)

    async def increment(self, key: str, amount: int, ttl_seconds: int) -> int:
        """整数累加 + 刷新 TTL；键不存在/非整数从 0 起算（fake 对脏值的宽容语义）。"""
        now = time.monotonic()
        current = self._values.get(key) if self._alive(key, now) else None
        try:
            new_value = int(current or 0) + amount
        except (TypeError, ValueError):
            new_value = amount
        self._values[key] = str(new_value)
        self._expiry[key] = now + max(ttl_seconds, 0)
        return new_value


class RedisKV:
    """真 Redis 实现（redis-py asyncio；构造惰性建连，首次调用才连接）。"""

    def __init__(self, url: str, timeout_seconds: float) -> None:
        self._redis = aioredis.from_url(url, socket_timeout=timeout_seconds, decode_responses=True)

    async def set_if_absent(self, key: str, ttl_seconds: int) -> bool:
        """SETNX+TTL（redis-py 返回 True/None，统一转 bool）。"""
        ok = await self._redis.set(key, "1", nx=True, ex=ttl_seconds)
        return bool(ok)

    async def get(self, key: str) -> str | None:
        return await self._redis.get(key)

    async def set(self, key: str, value: str, ttl_seconds: int) -> None:
        await self._redis.set(key, value, ex=ttl_seconds)

    async def increment(self, key: str, amount: int, ttl_seconds: int) -> int:
        """INCR + EXPIRE（非原子复合——成本累计场景可容忍，注释钉桩）。"""
        new_value = int(await self._redis.incrby(key, amount))
        await self._redis.expire(key, ttl_seconds)
        return new_value

    async def ping(self) -> bool:
        """连通探测（/health 专用；不在端口协议上）。"""
        return bool(await self._redis.ping())

    async def aclose(self) -> None:
        """释放连接（composition 收尾统一调用）。"""
        await self._redis.aclose()
