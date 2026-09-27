"""crosscutting/budget —— Redis 日累计成本键（G6：实时控制依据；Langfuse 事后对账）。

职责：quantabot:cost:{yyyymmdd} 键的读/累加；token 用量按单价折算为厘；
      M5 三档判定（classify_tier——充足/吃紧/枯竭，阈值含边界）。
边界：记账与分档判定在此，切换动作在管线（吃紧切轻模型/枯竭静默）；
      成本单位=厘（1 元=1000 厘）——Redis INCR 仅整数，用厘避免浮点漂移。
"""

from datetime import date
from typing import Literal

from quanta_bot.crosscutting.ports import KeyValueStore

# 成本键前缀（P0-7 键命名口径：quantabot: 命名空间）
COST_KEY_PREFIX = "quantabot:cost:"

# M5 成本三档（grill 共识：吃紧 20 元=20000 厘 / 枯竭 28 元=28000 厘，Settings 可配）
CostTier = Literal["sufficient", "tight", "exhausted"]


def classify_tier(
    daily_cost_li: int, tight_threshold_li: int, exhausted_threshold_li: int
) -> CostTier:
    """按当日累计成本判档：枯竭优先判定（阈值含边界——达到即切换）。"""
    if daily_cost_li >= exhausted_threshold_li:
        return "exhausted"
    if daily_cost_li >= tight_threshold_li:
        return "tight"
    return "sufficient"


def cost_key(day: date) -> str:
    """日累计成本键（UTC 日界）。"""
    return f"{COST_KEY_PREFIX}{day:%Y%m%d}"


async def read_cost(kv: KeyValueStore, day: date) -> int:
    """读当日累计成本（厘）；无键=0。"""
    raw = await kv.get(cost_key(day))
    return int(raw) if raw is not None else 0


async def add_cost(kv: KeyValueStore, cost_li: int, day: date, ttl_hours: int) -> int:
    """累加当日成本并刷新 TTL（EXPIRE 48h 防残留），返回累加后的值。"""
    return await kv.increment(cost_key(day), cost_li, ttl_seconds=ttl_hours * 3600)


def estimate_cost_li(
    prompt_tokens: int,
    completion_tokens: int,
    input_price_per_mtok: float,
    output_price_per_mtok: float,
) -> int:
    """按 token 用量与单价（元/百万 token）折算成本（厘，四舍五入）。"""
    yuan = (
        prompt_tokens * input_price_per_mtok + completion_tokens * output_price_per_mtok
    ) / 1_000_000
    return round(yuan * 1000)
