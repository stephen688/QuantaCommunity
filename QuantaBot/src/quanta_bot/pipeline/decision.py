"""pipeline/decision —— 决策层（该不该回、什么模式回）。

职责：M1 最小版——固定返回值得回；M3 落地低价值过滤+模式识别（技术选型 §5.6）。
边界：不做成本漏斗与 LLM 意图分类（M3）；幂等/审核拦截在链路上游（crosscutting）。
"""

from typing import Literal

from pydantic import BaseModel

from quanta_bot.pipeline.trigger import TriggerEvent

# 四表达模式（PRD F3；M1 仅作枚举占位，路由逻辑 M3）
Mode = Literal["专业答疑", "生活玩梗", "情绪陪伴", "治理"]


class DecisionResult(BaseModel):
    """决策器输出契约（M3 起由真实决策逻辑产出，含置信度与检索/记忆 flag）。"""

    should_reply: bool
    mode: Mode
    reason: str


def decide(event: TriggerEvent) -> DecisionResult:
    """M1 骨架固定决策：一切命中触发均值得回（真实过滤 M3 落地）。"""
    return DecisionResult(should_reply=True, mode="生活玩梗", reason="M1 骨架固定决策")
