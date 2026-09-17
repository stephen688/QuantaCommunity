"""pipeline/pipeline —— 核心链路组装（M3 全量：kill短路→触发→幂等→预检→硬规则→拉取→
记忆双段召回→一车四用决策→检索→四通道总装→生成→成本→写库→记忆落库+对话链→打点）。

职责：run() 单出口——所有回/不回分支统一落决策日志（诚实口径，PRD F6）+ RunTrace 上报
      （M3 增截断留痕/精选记忆 id/persona_version/检索降级四观测字段）；
      kill switch 短路（G5）；硬规则低价值拉取前零成本拦截（§5.6 ①）；LLM/写库失败静默记
      failed 不回（红线 §0.3）；记忆四态落库与对话链 append 在 replied 后执行（失败 WARNING
      不阻断已成功回复）。
边界：不建外部客户端（deps 由 composition 注入；人格/记忆/摘要三件 M3 起必填）；
      熔断/频率/消费暂停不在本层（M5/Tranche B）；检索片段渲染随 Task 13 接线
      （Retriever 端口落地前 deps.retriever 恒 None——need_retrieval 时降级留痕照常回复）。
已知坑：FakeLLM 剧本按调用序弹出——决策（json_mode）调用必须在生成调用之前（测试对齐依据）；
      对话链 read_chain/append_turn 走 kv 楼层链，read-modify-write 依赖同帖串行（M2 单消费者）。
"""

import logging
from collections.abc import Sequence
from dataclasses import dataclass
from datetime import UTC, datetime
from typing import TYPE_CHECKING

from quanta_bot.crosscutting import budget, idempotency, moderation
from quanta_bot.crosscutting.killswitch import ControlPlane
from quanta_bot.crosscutting.ports import (
    Decision,
    DecisionAudit,
    DecisionLogEntry,
    KeyValueStore,
    TruncationRecord,
)
from quanta_bot.memory import dialogue, user_memory
from quanta_bot.memory.ports import UserMemoryStore
from quanta_bot.pipeline import context, decision, generation, trigger
from quanta_bot.pipeline.persona import PersonaLibrary
from quanta_bot.pipeline.ports import (
    CommentFetchError,
    CommentTreeFetcher,
    LLMClient,
    LLMClientError,
    PostThread,
    ReplyWriteError,
    ReplyWriter,
    RunTrace,
    RunTracer,
    Summarizer,
)
from quanta_bot.pipeline.trigger import TriggerEvent

if TYPE_CHECKING:
    from quanta_bot.pipeline.ports import Retriever  # Task 13 落地端口定义（仅注解前向引用）

logger = logging.getLogger(__name__)


@dataclass
class PipelineDeps:
    """管线依赖（composition 装配注入；测试可自组；数值参数带默认值便于测试）。"""

    kv: KeyValueStore  # 幂等/成本键/对话链存储
    audit: DecisionAudit  # 决策审计
    reply_writer: ReplyWriter  # 回复写入器
    llm: LLMClient  # LLM 客户端
    tracer: RunTracer  # 运行跟踪器
    control_plane: ControlPlane  # kill switch 控制平面
    comment_tree: CommentTreeFetcher  # 评论树获取器
    persona: PersonaLibrary  # 人格库（A 通道 system prompt + persona_version 指纹）
    memory_store: UserMemoryStore  # 用户级记忆存储（粗召回/负面全量/四态落库）
    summarizer: Summarizer  # 远区楼层摘要器（C 通道）
    # 成本折算参数（composition 从 Settings 注入；默认=技术选型 §4.3 口径）
    llm_input_price_per_mtok: float = 12.0  # LLM 输入 token 价格
    llm_output_price_per_mtok: float = 24.0  # LLM 输出 token 价格
    cost_key_ttl_hours: int = 48  # 成本键 TTL 小时数
    # M3 链路参数（composition 从 Settings 注入；Task 13 前 retriever 恒 None=检索降级）
    retriever: "Retriever | None" = None  # RAG 检索端口（Task 13 落地）
    memory_recall_top_k: int = 8  # 记忆粗召回条数上限
    memory_select_max: int = 3  # 记忆精选上限（蓝图钉死 ≤3）
    rag_fragment_limit: int = 3  # RAG 检索片段数上限（计入预留预算）
    dialogue_memory_ttl_hours: int = 48  # 对话级记忆 TTL 小时数
    summary_cache_ttl_hours: int = 24  # 远区摘要缓存 TTL 小时数
    leak_extra_patterns: tuple[tuple[str, str], ...] = ()  # 防泄露附加模式（Task 19 消费，占位）


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
    # M3 扩展（观测对质与归因——RunTrace 透传字段）
    truncations: tuple[TruncationRecord, ...] = ()
    memory_selected_ids: tuple[str, ...] = ()
    persona_version: str | None = None
    retrieval_degraded: bool = False


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
            truncations=outcome.truncations,
            memory_selected_ids=outcome.memory_selected_ids,
            persona_version=outcome.persona_version,
            retrieval_degraded=outcome.retrieval_degraded,
        )
    )
    return outcome.decision


def _dialogue_history_lines(
    thread: PostThread, turns: Sequence[dialogue.DialogueTurn]
) -> list[str]:
    """对话链注入行：C-2③ bot_history 为权威，内容已存在的 turn 跳过（去重防重复渲染）。"""
    existing_contents = {node.content for node in thread.bot_history}
    return [
        f"【AI回复#{turn.turn_id}】{turn.reply_content}"
        for turn in turns
        if turn.reply_content not in existing_contents
    ]


async def _execute(event: TriggerEvent, deps: PipelineDeps) -> _Outcome:
    """执行 M3 链路各分支（不负责打点——run 统一出口处理）。"""
    # ⓪ kill switch 短路（G5 止血）
    if deps.control_plane.snapshot.kill:
        return _Outcome("skipped_killswitch", "kill switch 置位，链路短路不回")
    # ① 触发检测（结构化标记主判定 + 文本兜底）
    if not event.mentioned_bot and not trigger.detect_mention(event.content):
        return _Outcome("skipped_not_mentioned", "未命中 @")
    # ② 幂等（重复投递静默跳过）
    if not await idempotency.check_and_mark(deps.kv, event.comment_id):
        return _Outcome("skipped_idempotent", "重复投递，幂等拦截")
    # ③ 审核预检（红线：违规一律不出）
    verdict = await moderation.precheck(event.content)
    if not verdict.passed:
        return _Outcome("rejected_moderation", verdict.reason)
    # ④ 硬规则低价值过滤（零成本，拉取前拦截——§5.6 ①）
    low_value_reason = decision.hard_low_value(event.content)
    if low_value_reason is not None:
        return _Outcome("skipped_low_value", low_value_reason)

    decision_result: decision.DecisionResult | None = (
        None  # failed 归因锚（决策成功后链路炸时携带 mode）
    )
    try:
        # ⑤ 拉取现场（C-2①③ 线程 + C-2② 全量楼层）
        thread = await deps.comment_tree.fetch_context(event)
        floors = await deps.comment_tree.fetch_floors(event.post_id)
        # ⑥ 记忆粗召回（向量管"找得到"；负面 feedback 全量并行取）
        persona_version = deps.persona.persona_version
        candidates = await deps.memory_store.recall(
            event.commenter_user_id, persona_version, event.content, deps.memory_recall_top_k
        )
        negatives = await deps.memory_store.negative_feedback(
            event.commenter_user_id, persona_version
        )
        # ⑦ 决策一车四用（轻量调用收口；失败=failed 静默）
        nearby_digest = "\n".join(f"{node.user_id}：{node.content}" for node in thread.chain)
        decision_result = await decision.decide(
            event, deps.llm, thread.post.content[:500], nearby_digest, candidates
        )
        if not decision_result.should_reply:
            return _Outcome("skipped_decision", decision_result.reason, mode=decision_result.mode)
        # ⑧ 检索（need_retrieval 且检索可用；不可用降级留痕照常回复——场景 6 降级链路）
        retrieval_degraded = False
        if decision_result.need_retrieval:
            if deps.retriever is None:
                retrieval_degraded = True  # 检索未配置：降级直说不知道（生成侧 prompt 已含）
            else:
                # 消费方在 Task 13（渲染 retrieval_lines 注入预留区）——端口落地前 F841 压制
                fragments = list(  # noqa: F841
                    await deps.retriever.retrieve(event.content, limit=deps.rag_fragment_limit)
                )
        # ⑨ 四通道总装（B/C/D+预留；截断全部留痕；AI 历史区=C-2③ bot_history ∪ 对话链去重）
        selected = [
            record for record in candidates if record.memory_id in decision_result.memory_selection
        ]
        memory_text, memory_trunc = user_memory.render_memory_block(
            selected, negatives, deps.memory_select_max
        )
        dialogue_turns = await dialogue.read_chain(deps.kv, event.post_id)  # 同帖多轮（TTL 窗口内）
        assembled = await context.assemble(
            event,
            thread,
            floors,
            memory_text,
            memory_trunc,
            deps.summarizer,
            deps.kv,
            persona_version,
            deps.summary_cache_ttl_hours,
            extra_history_lines=_dialogue_history_lines(thread, dialogue_turns),
        )
        # ⑩ 生成（人格 system by mode）→ 成本 → ⑪ 写库
        output = await generation.generate(
            event, decision_result, deps.llm, assembled.user_text, deps.persona
        )
        cost_li = budget.estimate_cost_li(
            output.prompt_tokens,
            output.completion_tokens,
            deps.llm_input_price_per_mtok,
            deps.llm_output_price_per_mtok,
        )
        cost_after = await budget.add_cost(
            deps.kv, cost_li, datetime.now(UTC).date(), deps.cost_key_ttl_hours
        )
        await deps.reply_writer.write_reply(output.reply)
    except (CommentFetchError, LLMClientError, ReplyWriteError) as exc:
        # 已知失败类型静默不回（红线 §0.3）；决策已成功时携带 mode 归因（M2 口径保持）。
        # 2026-09-17 review I-1：拉取异常原裸逃 _execute 击穿 run() 单出口（无 RunTrace/
        # 无归因，consumer 兜底丢观测）——HTTPCommentTreeFetcher 现统一包装 CommentFetchError。
        return _Outcome(
            "failed",
            f"链路异常静默不回：{exc}",
            mode=decision_result.mode if decision_result is not None else None,
            error=str(exc),
        )

    # ⑫ replied 后：记忆四态落库 + 对话链 append（失败 WARNING 不阻断——回复已成功）
    if decision_result.memory_ops:
        try:
            await deps.memory_store.apply_ops(
                event.commenter_user_id, persona_version, decision_result.memory_ops
            )
        except Exception as exc:  # 记忆失败不回滚回复（观测 WARNING；熔断归 M5）
            logger.warning("记忆四态落库失败（不阻断回复）：%s", exc)
    try:
        await dialogue.append_turn(
            deps.kv,
            event.post_id,
            dialogue.DialogueTurn(
                turn_id=event.comment_id,
                reply_content=output.reply.content,
                created_at=datetime.now(UTC),
            ),
            deps.dialogue_memory_ttl_hours,
        )
    except Exception as exc:
        logger.warning("对话级记忆 append 失败（不阻断回复）：%s", exc)

    return _Outcome(
        "replied",
        f"链路完整（决策：{decision_result.reason}；模式 {decision_result.mode}；"
        f"本次成本 {cost_li} 厘，日累计 {cost_after} 厘）",
        mode=decision_result.mode,
        context_text=assembled.user_text,
        generated_content=output.reply.content,
        prompt_tokens=output.prompt_tokens,
        completion_tokens=output.completion_tokens,
        cost_li=cost_li,
        daily_cost_li_after=cost_after,
        truncations=assembled.truncations,
        memory_selected_ids=tuple(decision_result.memory_selection),
        persona_version=persona_version,
        retrieval_degraded=retrieval_degraded,
    )
