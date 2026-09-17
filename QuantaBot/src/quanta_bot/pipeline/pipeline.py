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
from quanta_bot.pipeline.persona import PersonaLibrary
from quanta_bot.pipeline.ports import (
    CommentTreeFetcher,
    LLMClient,
    LLMClientError,
    PostThread,
    ReplyWriteError,
    ReplyWriter,
    RunTrace,
    RunTracer,
)
from quanta_bot.pipeline.trigger import TriggerEvent

# 人格库模块级占位：启动即读 prompts/（文件缺失=启动失败，符合"人格不完整不可上线"）；
# Task 9 重构为 deps.persona 注入后移除本占位（M3 计划 Task 4 执行前增补条目）
_PERSONA = PersonaLibrary()


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


def _chain_digest(thread: PostThread) -> str:
    """决策用父链摘要（触发评论所在链逐行 user_id：content——Task 9 并入 floors 后扩展）。"""
    return "\n".join(f"{node.user_id}：{node.content}" for node in thread.chain)


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

    # ④ 决策（M3：硬规则前置 + 一车四用轻量调用；失败=failed 静默不回）
    low_value_reason = decision.hard_low_value(event.content)  # 硬规则零成本拦截（拉取前，§5.6 ①）
    if low_value_reason is not None:
        return _Outcome("skipped_low_value", low_value_reason)
    today = datetime.now(UTC).date()  # 今日日期
    cost_before = await budget.read_cost(deps.kv, today)  # 今日成本前
    d: decision.DecisionResult | None = (
        None  # 决策结果（decide 抛错时尚未产生——failed 记 mode=None）
    )
    try:
        thread = await deps.comment_tree.fetch_context(
            event
        )  # 决策材料：主楼+链（Task 9 并入 floors）
        d = await decision.decide(
            event, deps.llm, thread.post.content[:500], _chain_digest(thread), ()
        )  # 一车四用轻量调用（记忆候选 Task 9 接线前传空——memory_ops 自然为空）
        if not d.should_reply:
            return _Outcome("skipped_decision", d.reason, mode=d.mode)  # LLM 判不值得回
        # ⑤ 上下文 → ⑥ 生成（成本）→ ⑦ 写库
        context_text = await context.build_context(event, deps.comment_tree)  # 上下文文本
        output = await generation.generate(  # 生成回复（人格 system 由 _PERSONA 组装）
            event, d, deps.llm, context_text, persona=_PERSONA
        )
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
            mode=d.mode if d is not None else None,
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
