"""crosscutting/rate_limit —— 同帖共享及同用户跨帖的尝试配额。

职责：每次尝试累加计数并刷新静默期；超限尝试也计数，停止尝试满静默期才恢复。
边界：非滚动窗口、非成功回复计数；存储异常由管线记录 WARNING 并 fail-open。
"""

from quanta_bot.crosscutting.ports import KeyValueStore

RATELIMIT_POST_PREFIX = "quantabot:ratelimit:post:"
RATELIMIT_USER_PREFIX = "quantabot:ratelimit:user:"


async def check_and_count(
    kv: KeyValueStore,
    *,
    post_id: int,
    user_id: int,
    post_limit: int,
    post_window_hours: int,
    user_daily_limit: int,
) -> tuple[bool, str]:
    """计入本次尝试并检查两项配额；同帖所有用户共享，拒绝也刷新静默期。"""
    post_after = await kv.increment(
        f"{RATELIMIT_POST_PREFIX}{post_id}", 1, post_window_hours * 3600
    )  # ① 同帖共享配额：每次尝试重置静默期
    user_after = await kv.increment(
        f"{RATELIMIT_USER_PREFIX}{user_id}", 1, 24 * 3600
    )  # ② 同用户跨帖配额：超限尝试也计入
    if post_after > post_limit:
        return False, f"同帖尝试配额耗尽（{post_after}>{post_limit}），需静默{post_window_hours}h"
    if user_after > user_daily_limit:
        return False, f"同用户尝试配额耗尽（{user_after}>{user_daily_limit}），需静默24h"
    return True, ""
