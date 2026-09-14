"""crosscutting/idempotency —— comment_id 幂等去重（缺口3，红线 §0.4）。

职责：以 comment_id 为业务唯一键判定首次/重复（Outbox 至少一次投递 → 重复是常态）。
边界：不感知 message id（投递层概念，仅辅助）；存储经 KeyValueStore 端口注入（不 import infra）。
"""

from quanta_bot.crosscutting.ports import KeyValueStore

# 幂等键前缀（与 kill switch/成本键同走 quantabot: 命名空间）
IDEMPOTENCY_KEY_PREFIX = "quantabot:idem:"
# 幂等键 TTL：7 天覆盖 MQ 重投窗口（占位值，M2 联调按重投实测校准）
IDEMPOTENCY_TTL_SECONDS = 7 * 24 * 3600


async def check_and_mark(kv: KeyValueStore, comment_id: int) -> bool:
    """首次见到该 comment_id 返回 True 并落键；重复返回 False（调用方应静默跳过）。"""
    return await kv.set_if_absent(f"{IDEMPOTENCY_KEY_PREFIX}{comment_id}", IDEMPOTENCY_TTL_SECONDS)
