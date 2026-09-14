"""pipeline/context —— 上下文组装（M1 stub）。

职责：M1 返回占位上下文；M2 接主服务评论树接口、M3 落地筛选策略+长度预算+帖子隔离。
边界：不触碰网络（评论树客户端在 infra，M2 引入）。
"""

from quanta_bot.pipeline.trigger import TriggerEvent


def build_context(event: TriggerEvent) -> str:
    """M1 占位上下文（真实楼层脉络 M2 起接入）。"""
    return f"主楼与楼层脉络（M1 占位）——触发评论：{event.content}"
