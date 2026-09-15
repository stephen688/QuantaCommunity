"""pipeline/pipeline —— 核心链路组装（触发→幂等→预检→决策→上下文→生成→写库→打点）。

职责：async run() 串联全链路；每个回/不回分支落决策日志（诚实口径，PRD F6）；
      返回决策枚举值供调用方（M2 consumer）与集成测试断言。
边界：不建外部客户端（deps 由 composition 注入）；不做熔断/超时档/串行（M2/M5 落地）；
      链路异常由调用方兜底记 failed（本函数不吞异常——处理不了的让它炸，AGENTS.md §4.3）。
"""

from dataclasses import dataclass

from quanta_bot.crosscutting import idempotency, moderation
from quanta_bot.crosscutting.ports import Decision, DecisionAudit, DecisionLogEntry, KeyValueStore
from quanta_bot.pipeline import context, decision, generation, trigger
from quanta_bot.pipeline.ports import CommentTreeFetcher, LLMClient, ReplyWriter
from quanta_bot.pipeline.trigger import TriggerEvent


@dataclass
class PipelineDeps:
    """管线依赖（composition 装配注入；测试可自组）。"""

    kv: KeyValueStore
    audit: DecisionAudit
    reply_writer: ReplyWriter
    comment_tree: CommentTreeFetcher
    llm: LLMClient


# 打点辅助函数（所有回/不回分支统一走这里，防漏记）
async def _audit(
    deps: PipelineDeps, comment_id: int, d: Decision, reason: str, mode: str | None = None
) -> None:
    """打点辅助（所有回/不回分支统一走这里，防漏记）。"""
    await deps.audit.record(
        DecisionLogEntry(comment_id=comment_id, decision=d, mode=mode, reason=reason)
    )


async def run(event: TriggerEvent, deps: PipelineDeps) -> Decision:
    """跑一条触发事件的完整被动链路，返回终态决策值。"""
    # ① 触发检测（未 @ → 链路不进入，幂等键不占）
    if not trigger.detect_mention(event.content):
        await _audit(deps, event.comment_id, "skipped_not_mentioned", "未命中 @，链路未进入")
        return "skipped_not_mentioned"

    # ② 幂等（重复投递 → 静默跳过）
    if not await idempotency.check_and_mark(deps.kv, event.comment_id):
        await _audit(deps, event.comment_id, "skipped_idempotent", "重复投递，幂等拦截")
        return "skipped_idempotent"

    # ③ 审核预检（红线：违规一律不出）
    verdict = await moderation.precheck(event.content)
    if not verdict.passed:
        await _audit(deps, event.comment_id, "rejected_moderation", verdict.reason)
        return "rejected_moderation"

    # ④ 决策 → ⑤ 上下文 → ⑥ 生成 → ⑦ 写库
    d = decision.decide(event)
    context_text = await context.build_context(event, deps.comment_tree)
    output = await generation.generate(event, d, deps.llm, context_text)
    await deps.reply_writer.write_reply(output.reply)
    await _audit(deps, event.comment_id, "replied", f"链路完整（决策：{d.reason}）", mode=d.mode)
    return "replied"
