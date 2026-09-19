"""Promptfoo Python provider：把合成攻击输入送入完整 QuantaBot 管线。

边界：不裸调模型、不持久化 prompt/用户原文；完整输出仅由 Promptfoo raw artifact 保存。
"""

import sys
from pathlib import Path

PROJECT_ROOT = Path(__file__).resolve().parents[1]
if str(PROJECT_ROOT) not in sys.path:
    sys.path.insert(0, str(PROJECT_ROOT))

from tests.eval._runner import CaseResult, EvalCase  # noqa: E402
from tests.eval._target import run_target_case  # noqa: E402

from quanta_bot.crosscutting.leak_scan import sanitize  # noqa: E402

_SILENT_OUTPUT = "[安全拦截：QuantaBot 管线已静默，不会公开危险内容]"


async def _run_prompt(prompt: str) -> CaseResult:
    """用合成 ID 构造治理场景，并执行与 Persona gate 相同的完整管线。"""
    case = EvalCase(
        id="redteam-synthetic",
        scenario=14,
        scenario_name="Promptfoo 合成红队输入",
        mode="治理",
        tier="persona",
        trigger={
            "post": {
                "postId": 990001,
                "userId": 990001,
                "title": "合成红队 fixture",
                "content": "这是不含真实用户数据的安全测试帖子。",
            },
            "comment": {
                "commentId": 990002,
                "userId": 990002,
                "content": f"@框框 {prompt}",
            },
            "floors": [],
        },
        deterministic=[],
        judge=None,
    )
    return await run_target_case(case)


async def call_api(prompt: str, options: dict, context: dict) -> dict:
    """Promptfoo 入口；返回可评分输出、guardrail 层级与脱敏元数据。"""
    try:
        result = await _run_prompt(prompt)
    except Exception as exc:  # provider 边界必须按 Promptfoo 契约返回 error
        return {"error": sanitize(f"{type(exc).__name__}: {exc}").content[:240]}

    trace = result.trace
    if result.decision == "failed":
        error = trace.error if trace and trace.error else "pipeline failed"
        return {"error": sanitize(error).content[:240]}
    flagged = result.decision != "replied"
    prompt_tokens = trace.prompt_tokens if trace and trace.prompt_tokens else 0
    completion_tokens = trace.completion_tokens if trace and trace.completion_tokens else 0
    return {
        "output": result.reply if result.reply else _SILENT_OUTPUT,
        "tokenUsage": {
            "prompt": prompt_tokens,
            "completion": completion_tokens,
            "total": prompt_tokens + completion_tokens,
        },
        "cost": (trace.cost_li / 1000) if trace and trace.cost_li else 0,
        "cached": False,
        "guardrails": {"flagged": flagged},
        "metadata": {
            "case_id": "redteam-synthetic",
            "decision": result.decision,
            "mode": trace.mode if trace else None,
            "leak_hits": list(trace.leak_hits) if trace else [],
            "persona_version": trace.persona_version if trace else None,
            "cost_currency": "CNY",
        },
    }
