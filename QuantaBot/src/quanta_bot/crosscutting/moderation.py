"""crosscutting/moderation —— 规则预检（D7 第一道，红线 §0.4：审核从第一条回复存在）。

职责：回复前置审核第一道——敏感词/显性违规拦截（M1 占位词表，M3 扩运营词表与规则模式）。
边界：只做规则预检，不做语义级审核；主服务审核接口第二道在 M2 接入（届时补双层拦截用例）。
"""

from pydantic import BaseModel

# 占位敏感词表：仅用于 M1 验收用例与结构验证；真实词表 M3 由运营侧定（词表外置为数据后在此读取）
SENSITIVE_WORDS: tuple[str, ...] = ("测试敏感词",)


class ModerationVerdict(BaseModel):
    """预检结论（passed=False → 链路静默不回 + 决策日志记 rejected_moderation）。"""

    passed: bool
    reason: str


async def precheck(content: str) -> ModerationVerdict:
    """命中任一敏感词即不通过（同步纯逻辑，async 签名保持链路 IO 形态一致）。"""
    for word in SENSITIVE_WORDS:
        if word in content:
            return ModerationVerdict(passed=False, reason=f"规则预检命中敏感词：{word}")
    return ModerationVerdict(passed=True, reason="规则预检通过（规则层无命中）")
