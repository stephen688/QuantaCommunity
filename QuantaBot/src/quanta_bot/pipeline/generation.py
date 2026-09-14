"""pipeline/generation —— 生成层（M1 固定文本 stub）。

职责：M1 返回带 AI 身份标识的固定回复；M3 落地人格内核+四模式 prompt（prompts/ 数据）。
边界：不内联人格 prompt 字符串（红线：prompts/ 是数据不是代码，M3 起只读）；不调 LLM（M2 客户端引入）。
"""

from pydantic import BaseModel

from quanta_bot.pipeline.decision import DecisionResult
from quanta_bot.pipeline.trigger import TriggerEvent

# 固定回复文本（带 AI 身份标识——红线 §0.1：每条回复可被一眼识别为 AI）
_FIXED_REPLY = "[QuantaBot·AI 学长] 骨架测试回复：收到你的 @ 啦，等 M3 我就有真人格了～"


class GeneratedReply(BaseModel):
    """生成结果契约（M2 起随写库接口契约对齐 [Phase 0 对齐点 P0-5]）。"""

    post_id: int
    reply_to_comment_id: int
    content: str


def generate(event: TriggerEvent, decision: DecisionResult | None = None) -> GeneratedReply:
    """M1 固定文本生成（decision 参数为链路形态占位，M3 起驱动模式 prompt）。"""
    return GeneratedReply(
        post_id=event.post_id,
        reply_to_comment_id=event.comment_id,
        content=_FIXED_REPLY,
    )
