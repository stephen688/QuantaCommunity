"""crosscutting/budget —— Redis 日累计成本键（G6：实时控制依据；Langfuse 事后对账）。

职责：quantabot:cost:{yyyymmdd} 键的读/累加；token 用量按单价折算为厘。
边界：只做记账，不做分档切换（充足/吃紧/枯竭三档是 M5-成本分档全量的范围）；
      成本单位=厘（1 元=1000 厘）——Redis INCR 仅整数，用厘避免浮点漂移。
"""

from datetime import date

from quanta_bot.crosscutting.ports import KeyValueStore

# 成本键前缀（P0-7 键命名口径：quantabot: 命名空间）
COST_KEY_PREFIX = "quantabot:cost:"


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
