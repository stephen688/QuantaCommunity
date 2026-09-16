"""pipeline/pipeline —— 核心链路组装（触发→kill短路→幂等→预检→决策→上下文→生成→成本→写库→打点）。

职责：run() 单出口——所有回/不回分支统一落决策日志（诚实口径，PRD F6）+ RunTrace 上报；
      kill switch 短路（G5）；LLM/写库失败静默记 failed 不回（红线 §0.3）。
边界：不建外部客户端（deps 由 composition 注入）；熔断/频率/消费暂停不在本层（M5/Tranche B）；
      除 LLM/写库两类已知失败外不吞异常（处理不了的让它炸，AGENTS §4.3）。
"""

from dataclasses import dataclass
from datetime import UTC, datetime

from quanta_bot.crosscutting import budget, idempotency, moderation
from quanta_bot.crosscutting.killswitch import ControlPlane
from quanta_bot.crosscutting.ports import Decision, DecisionAudit, DecisionLogEntry, KeyValueStore
from quanta_bot.pipeline import context, decision, generation, trigger
from quanta_bot.pipeline.ports import (
    CommentTreeFetcher,
    LLMClient,
    LLMClientError,
    ReplyWriteError,
    ReplyWriter,
    RunTrace,
    RunTracer,
)
from quanta_bot.pipeline.trigger import TriggerEvent


@dataclass
class PipelineDeps:
    """管线依赖（composition 装配注入；测试可自组；价格/TTL 带默认值便于测试）。"""

    kv: KeyValueStore  # 幂等键存储
    audit: DecisionAudit  # 决策审计
    reply_writer: ReplyWriter  # 回复写入器
    llm: LLMClient  # LLM 客户端
    tracer: RunTracer  # 运行跟踪器
    control_plane: ControlPlane  # kill switch 控制平面
    comment_tree: CommentTreeFetcher  # 评论树获取器
    # 成本折算参数（composition 从 Settings 注入；默认=技术选型 §4.3 口径）
    llm_input_price_per_mtok: float = 12.0  # LLM 输入 token 价格
    llm_output_price_per_mtok: float = 24.0  # LLM 输出 token 价格
    cost_key_ttl_hours: int = 48  # 成本键 TTL 小时数


@dataclass
class _Outcome:
    """单分支执行结果（决策明细 + trace 的公共字段载体）。"""

    decision: Decision
    reason: str
    mode: str | None = None
    context_text: str | None = None
    generated_content: str | None = None
    prompt_tokens: int | None = None
    completion_tokens: int | None = None
    cost_li: int | None = None
    daily_cost_li_after: int | None = None
    error: str | None = None


async def run(event: TriggerEvent, deps: PipelineDeps) -> Decision:
    """跑一条触发事件的完整被动链路，返回终态决策值（单出口统一审计+上报）。"""
    outcome = await _execute(event, deps)  # 执行链路各分支
    await deps.audit.record(  # 记录决策日志
        DecisionLogEntry(
            comment_id=event.comment_id,
            decision=outcome.decision,
            mode=outcome.mode,
            reason=outcome.reason,
        )
    )
    await deps.tracer.record(  # 上报运行跟踪
        RunTrace(
            comment_id=event.comment_id,
            post_id=event.post_id,
            trigger_content=event.content,
            decision=outcome.decision,
            mode=outcome.mode,
            reason=outcome.reason,
            context_text=outcome.context_text,
            generated_content=outcome.generated_content,
            prompt_tokens=outcome.prompt_tokens,
            completion_tokens=outcome.completion_tokens,
            cost_li=outcome.cost_li,
            daily_cost_li_after=outcome.daily_cost_li_after,
            error=outcome.error,
        )
    )
    return outcome.decision


async def _execute(event: TriggerEvent, deps: PipelineDeps) -> _Outcome:
    """执行链路各分支（不负责打点——run 统一出口处理）。"""
    # ⓪ kill switch 短路（G5 止血：置位期间零新回复，幂等键不占）
    if deps.control_plane.snapshot.kill:
        return _Outcome("skipped_killswitch", "kill switch 置位，链路短路不回")

    # ① 触发检测（主判定=结构化标记 mentioned_bot（C-4）；文本兜底=前端标记缺失的降级路径）
    if not event.mentioned_bot and not trigger.detect_mention(event.content):
        return _Outcome("skipped_not_mentioned", "未命中 @（结构化标记与文本兜底均未命中）")

    # ② 幂等（重复投递 → 静默跳过）
    if not await idempotency.check_and_mark(deps.kv, event.comment_id):
        return _Outcome("skipped_idempotent", "重复投递，幂等拦截")

    # ③ 审核预检（红线：违规一律不出）
    verdict = await moderation.precheck(event.content)
    if not verdict.passed:
        return _Outcome("rejected_moderation", verdict.reason)

    # ④ 决策 → ⑤ 上下文 → ⑥ 生成（成本）→ ⑦ 写库
    d = decision.decide(event)  # 决策
    today = datetime.now(UTC).date()  # 今日日期
    cost_before = await budget.read_cost(deps.kv, today)  # 今日成本前
    try:
        context_text = await context.build_context(event, deps.comment_tree)  # 上下文文本
        output = await generation.generate(event, d, deps.llm, context_text)  # 生成回复
        cost_li = budget.estimate_cost_li(  # 成本折算
            output.prompt_tokens,
            output.completion_tokens,
            deps.llm_input_price_per_mtok,
            deps.llm_output_price_per_mtok,
        )
        cost_after = await budget.add_cost(
            deps.kv, cost_li, today, deps.cost_key_ttl_hours
        )  # 今日成本后
        # ⑦ 写库
        await deps.reply_writer.write_reply(output.reply)  # 写回复回复
    except (LLMClientError, ReplyWriteError) as exc:
        # 已知失败类型静默不回（红线 §0.3）；其余异常按 AGENTS §4.3 让它炸
        return _Outcome(
            "failed",
            f"链路异常静默不回：{exc}",
            mode=d.mode,
            error=str(exc),
        )
    return _Outcome(
        "replied",
        f"链路完整（决策：{d.reason}；本次成本 {cost_li} 厘，日累计 {cost_after} 厘，调前 {cost_before} 厘）",
        mode=d.mode,
        context_text=context_text,
        generated_content=output.reply.content,
        prompt_tokens=output.prompt_tokens,
        completion_tokens=output.completion_tokens,
        cost_li=cost_li,
        daily_cost_li_after=cost_after,
    )
