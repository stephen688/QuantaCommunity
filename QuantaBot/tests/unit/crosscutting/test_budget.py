"""budget 成本键（G6）行为测试：键名、读写、折算。"""

from datetime import date

from quanta_bot.crosscutting import budget
from quanta_bot.crosscutting.budget import classify_tier
from quanta_bot.infra.kv import InMemoryKV


def test_cost_key_format() -> None:
    """键名 quantabot:cost:{yyyymmdd}（P0-7 命名口径）。"""
    assert budget.cost_key(date(2026, 9, 14)) == "quantabot:cost:20260914"


async def test_read_cost_defaults_zero_and_accumulates() -> None:
    """无键=0；add_cost 累加返回新值。"""
    kv = InMemoryKV()
    day = date(2026, 9, 14)
    assert await budget.read_cost(kv, day) == 0
    assert await budget.add_cost(kv, 8, day, ttl_hours=48) == 8
    assert await budget.add_cost(kv, 8, day, ttl_hours=48) == 16
    assert await budget.read_cost(kv, day) == 16


def test_estimate_cost_li() -> None:
    """token 折算厘：1M 输入@12 元 → 12000 厘；小额四舍五入。"""
    assert budget.estimate_cost_li(1_000_000, 0, 12.0, 24.0) == 12000
    assert budget.estimate_cost_li(0, 500_000, 12.0, 24.0) == 12000
    assert budget.estimate_cost_li(500, 100, 12.0, 24.0) == 8  # (6000+2400)/1e6 元 → 8.4 厘 → 8


# ---- M5 三档判定 ----


async def test_classify_tier_boundaries() -> None:
    """三档判定边界：枯竭优先、阈值含边界（达到即切换）。"""
    assert classify_tier(0, 20000, 28000) == "sufficient"
    assert classify_tier(19999, 20000, 28000) == "sufficient"  # 阈值前一分属充足
    assert classify_tier(20000, 20000, 28000) == "tight"  # 达吃紧阈值即吃紧
    assert classify_tier(27999, 20000, 28000) == "tight"
    assert classify_tier(28000, 20000, 28000) == "exhausted"  # 达枯竭阈值即枯竭
