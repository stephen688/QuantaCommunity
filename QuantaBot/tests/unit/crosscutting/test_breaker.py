"""breaker 状态机单测：closed→open→half_open 全转换路径（clock 注入加速，不 sleep）。"""

import pytest

from quanta_bot.crosscutting.breaker import BreakerOpenError, CircuitBreaker


class FakeClock:
    """可拨动的单调时钟（open 计时测试用）。"""

    def __init__(self) -> None:
        self.now = 100.0

    def __call__(self) -> float:
        return self.now


def _make_breaker(
    threshold: int = 5, open_seconds: float = 60.0
) -> tuple[CircuitBreaker, FakeClock]:
    clock = FakeClock()
    return CircuitBreaker("llm", threshold, open_seconds, clock=clock), clock


async def test_closed_allows_and_success_resets_count() -> None:
    breaker, _ = _make_breaker()
    for _ in range(4):
        breaker.record_failure()
    assert breaker.state == "closed"  # 4 次 < 阈值 5，仍放行
    breaker.record_success()  # 成功清零
    for _ in range(4):
        breaker.record_failure()
    assert breaker.state == "closed"  # 清零后 4 次仍不开
    breaker.before_call()  # closed 放行不抛


async def test_open_after_consecutive_failures_and_blocks() -> None:
    breaker, _ = _make_breaker(threshold=5, open_seconds=60.0)
    for _ in range(5):
        breaker.record_failure()
    assert breaker.state == "open"
    with pytest.raises(BreakerOpenError):
        breaker.before_call()  # open 期拒绝调用


async def test_half_open_probe_success_closes() -> None:
    breaker, clock = _make_breaker(threshold=2, open_seconds=30.0)
    breaker.record_failure()
    breaker.record_failure()
    assert breaker.state == "open"
    clock.now += 31.0  # 越过 open 保持期
    breaker.before_call()  # 到期 → 转 half_open 并放行探测
    assert breaker.state == "half_open"
    breaker.record_success()  # 探测成功 → closed 清零
    assert breaker.state == "closed"
    breaker.before_call()


async def test_half_open_probe_failure_reopens() -> None:
    breaker, clock = _make_breaker(threshold=2, open_seconds=30.0)
    breaker.record_failure()
    breaker.record_failure()
    clock.now += 31.0
    breaker.before_call()  # half_open 探测放行
    breaker.record_failure()  # 探测失败 → 回 open 重新计时
    assert breaker.state == "open"
    with pytest.raises(BreakerOpenError):
        breaker.before_call()  # 重新 open 期继续拒绝
    clock.now += 31.0
    breaker.before_call()  # 再次到期仍可探测（恢复通路不锁死）


async def test_open_within_cooldown_still_blocks() -> None:
    breaker, clock = _make_breaker(threshold=1, open_seconds=60.0)
    breaker.record_failure()
    clock.now += 59.0  # 未到 60s
    with pytest.raises(BreakerOpenError):
        breaker.before_call()
