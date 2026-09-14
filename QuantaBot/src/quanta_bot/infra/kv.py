"""infra/kv —— KeyValueStore 端口实现（M1：内存 fake；M2：真 Redis 客户端）。

职责：fake_mode 下提供内存版 SETNX+TTL 语义，供幂等/开关/成本键共用。
边界：不持久化（重启即失——fake 定位）；过期键仅惰性判断、不主动清理（长跑内存缓增——
fake 定位可接受，M1 审查 Minor-4 注记）；M2 在本文件替换为 redis 实现，端口与调用方零改。
"""

import time


class InMemoryKV:
    """内存 fake（dict[键, 过期时刻]；monotonic 时钟避免系统时间回拨干扰）。"""

    def __init__(self) -> None:
        self._expiry: dict[str, float] = {}

    async def set_if_absent(self, key: str, ttl_seconds: int) -> bool:
        """SETNX+TTL：键不存在或已过期 → 设置并 True；存在且未过期 → False。"""
        now = time.monotonic()
        expire_at = self._expiry.get(key)
        if expire_at is not None and expire_at > now:
            return False
        self._expiry[key] = now + max(ttl_seconds, 0)
        return True
