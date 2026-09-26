"""尝试配额回归：阈值、用户跨帖限制、共享帖子配额及每次尝试重置静默期。"""

import pytest

from quanta_bot.crosscutting import rate_limit
from quanta_bot.infra.kv import InMemoryKV


async def test_post_quota_is_shared_and_blocked_attempt_extends_silence(monkeypatch):
    now = [100.0]
    monkeypatch.setattr("quanta_bot.infra.kv.time.monotonic", lambda: now[0])
    kv = InMemoryKV()
    options = dict(post_id=1, post_limit=3, post_window_hours=48, user_daily_limit=5)
    for user_id in (7, 8, 9):
        assert (await rate_limit.check_and_count(kv, user_id=user_id, **options))[0]
    now[0] += 47 * 3600
    assert not (await rate_limit.check_and_count(kv, user_id=10, **options))[0]
    now[0] += 2 * 3600  # 距首条已49h，但距最新尝试仅2h，仍未恢复
    assert not (await rate_limit.check_and_count(kv, user_id=11, **options))[0]
    now[0] += 48 * 3600 + 1
    assert (await rate_limit.check_and_count(kv, user_id=12, **options))[0]


async def test_user_quota_counts_across_posts():
    kv = InMemoryKV()
    for post_id in range(5):
        assert (
            await rate_limit.check_and_count(
                kv,
                post_id=post_id,
                user_id=7,
                post_limit=3,
                post_window_hours=48,
                user_daily_limit=5,
            )
        )[0]
    allowed, reason = await rate_limit.check_and_count(
        kv, post_id=10, user_id=7, post_limit=3, post_window_hours=48, user_daily_limit=5
    )
    assert allowed is False and "用户" in reason


async def test_rate_storage_failure_is_not_converted_to_success():
    class UnavailableKV:
        async def increment(self, key, amount, ttl_seconds):
            raise RuntimeError("redis unavailable")

    with pytest.raises(RuntimeError, match="redis unavailable"):
        await rate_limit.check_and_count(
            UnavailableKV(),
            post_id=1,
            user_id=7,
            post_limit=3,
            post_window_hours=48,
            user_daily_limit=5,
        )
