"""pipeline/pipeline —— 核心链路组装（M3 全量：kill短路→触发→幂等→预检→硬规则→拉取→
记忆双段召回→一车四用决策→检索→四通道总装→生成→成本→写库→记忆落库+对话链→打点；
M5 接入三份三态熔断：llm open=failed_breaker 静默 / memory open=降级空候选留痕 /
main_service open=failed 不回；M5 成本三档：枯竭=skipped_cost_exhausted 规则兜底静默 /
吃紧=仅生成切轻模型（决策/摘要保持主模型保判断质量）、读档失败 fail-open 按充足）。

职责：run() 单出口——所有回/不回分支统一落决策日志（诚实口径，PRD F6）+ RunTrace 上报
      （M3 增截断留痕/精选记忆 id/persona_version/检索降级/leak_hits 五观测字段，M5 增
      memory_degraded 降级留痕）；kill switch 短路（G5）；硬规则低价值拉取前零成本拦截
      （§5.6 ①）；LLM/写库失败静默记 failed 不回（红线 §0.3）；记忆四态落库与对话链
      append 在 replied 后执行（失败 WARNING 不阻断已成功回复）。
边界：不建外部客户端（deps 由 composition 注入；人格/记忆/摘要三件 M3 起必填）；
      频率/消费暂停不在本层（Tranche B）；M5 熔断经 deps.breakers 注入（缺省=共识档
      closed 全放行，行为透明）；检索片段由 Task 13 的 Retriever 端口注入，未配置或
      检索失败时 need_retrieval 走降级留痕并照常回复。
已知坑：FakeLLM 剧本按调用序弹出——决策（json_mode）调用必须在生成调用之前（测试对齐依据）；
      对话链 read_chain/append_turn 走 kv 楼层链，read-modify-write 依赖同帖串行（M2 单消费者）。
"""

import inspect
import logging
import time
from collections.abc import Awaitable, Sequence
from dataclasses import dataclass, field
from datetime import UTC, datetime
from typing import TYPE_CHECKING, TypeVar

from quanta_bot.crosscutting import budget, idempotency, leak_scan, moderation, rate_limit
from quanta_bot.crosscutting.breaker import BreakerOpenError, BreakerSet
from quanta_bot.crosscutting.killswitch import ControlPlane
from quanta_bot.crosscutting.ports import (
    Decision,
    DecisionAudit,
    DecisionLogEntry,
    KeyValueStore,
    TruncationRecord,
)
from quanta_bot.crosscutting.trace_context import current_event_id, current_trace_id
from quanta_bot.memory import dialogue, user_memory
from quanta_bot.memory.ports import UserMemoryStore
from quanta_bot.pipeline import context, decision, generation, trigger
from quanta_bot.pipeline.context import RESERVED_BUDGET
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


async def _apply_memory_ops(
    store: UserMemoryStore,
    user_id: int,
    persona_version: str,
    ops: Sequence,
    source_event_id: str,
) -> None:
    """写入记忆并向支持新端口的实现传递稳定 Outbox event id。

    旧的测试/fake 实现可能仍是三参数端口；兼容它们不会影响真实 Qdrant，且避免
    用捕获 TypeError 的方式掩盖存储实现内部错误。
    """
    apply_ops = store.apply_ops
    parameters = inspect.signature(apply_ops).parameters.values()
    supports_source_id = any(
        parameter.name == "source_event_id" or parameter.kind is inspect.Parameter.VAR_KEYWORD
        for parameter in parameters
    )
    if supports_source_id:
        await apply_ops(user_id, persona_version, ops, source_event_id=source_event_id)
    else:
        await apply_ops(user_id, persona_version, ops)


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
    # M3 链路参数（composition 从 Settings 注入；retriever=None=检索降级）
    retriever: "Retriever | None" = None  # RAG 检索端口（Task 13 落地）
    memory_recall_top_k: int = 8  # 记忆粗召回条数上限
    memory_select_max: int = 3  # 记忆精选上限（蓝图钉死 ≤3）
    rag_fragment_limit: int = 3  # RAG 检索片段数上限（计入预留预算）
    dialogue_memory_ttl_hours: int = 48  # 对话级记忆 TTL 小时数
    summary_cache_ttl_hours: int = 24  # 远区摘要缓存 TTL 小时数
    leak_extra_patterns: tuple[tuple[str, str], ...] = ()  # 防泄露附加模式（Task 19 消费，占位）
    # M5 横切（composition 注入 Settings 档；缺省=共识默认档 closed 全放行——既有测试/eval 组装零改动）
    breakers: BreakerSet = field(
        default_factory=BreakerSet
    )  # 三份熔断器（llm/memory/main_service）
    llm_light: "LLMClient | None" = None  # M5 轻模型（吃紧档生成专用；None=未配置回落主模型）
    light_llm_input_price_per_mtok: float = (
        0.8  # 技术选型 §4.3 Qwen3.5-Plus；输出价实施期以官方页校准
    )
    light_llm_output_price_per_mtok: float = 4.8
    cost_tight_threshold_li: int = 20000  # 吃紧阈值（厘）= 20 元
    cost_exhausted_threshold_li: int = 28000  # 枯竭阈值（厘）= 28 元
    post_reply_limit: int = 3  # 同帖共享尝试配额，非成功回复计数
    post_rate_window_hours: int = 48  # 每次尝试刷新静默期
    user_daily_limit: int = 5  # 同用户跨帖尝试配额，静默24h恢复


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
    generation_usage_complete: bool | None = None
    generation_validation_failed: bool = False
    cost_li: int | None = None
    daily_cost_li_after: int | None = None
    error: str | None = None
    # M3 扩展（观测对质与归因——RunTrace 透传字段）
    truncations: tuple[TruncationRecord, ...] = ()
    memory_selected_ids: tuple[str, ...] = ()
    persona_version: str | None = None
    retrieval_degraded: bool = False
    leak_hits: tuple[str, ...] = ()
    memory_degraded: bool = False  # M5：记忆熔断 open/召回失败降级留痕
    cost_tier: str | None = None  # M5：本次 run 的成本档位
    light_model_used: bool = False  # M5：生成是否走了轻模型（吃紧档切换留痕）
    stage_ms: dict[str, int] = field(default_factory=dict)


@dataclass
class _RunHealth:
    """依赖在本 run 内的调用与失败状态；全部调用结束后只结算一次。"""

    llm_used: bool = False
    llm_failed: bool = False
    memory_used: bool = False
    memory_failed: bool = False
    main_service_used: bool = False
    main_service_failed: bool = False
    stage_ms: dict[str, int] = field(default_factory=dict)


ResultType = TypeVar("ResultType")


async def _timed(stages: dict[str, int], name: str, operation: Awaitable[ResultType]) -> ResultType:
    """记录实际执行阶段的处理耗时，失败也保留该阶段时长。"""
    started = time.monotonic()
    try:
        return await operation
    finally:
        stages[name] = round((time.monotonic() - started) * 1000)


async def run(event: TriggerEvent, deps: PipelineDeps) -> Decision:
    """跑一条触发事件的完整被动链路，返回终态决策值（单出口统一审计+上报）。"""
    started_at = time.monotonic()  # ① 管线计时：不包含MQ排队和主服务异步终审
    outcome = await _execute(event, deps)  # 执行链路各分支
    duration_ms = round((time.monotonic() - started_at) * 1000)
    logger.info(
        "bot_pipeline_result commentId=%s decision=%s durationMs=%s stageMs=%s",
        event.comment_id,
        outcome.decision,
        duration_ms,
        outcome.stage_ms,
    )
    await deps.audit.record(  # 记录决策日志
        DecisionLogEntry(
            comment_id=event.comment_id,
            decision=outcome.decision,
            mode=outcome.mode,
            reason=outcome.reason,
            duration_ms=duration_ms,
            cost_li=outcome.cost_li,
        )
    )
    await deps.tracer.record(  # 上报运行跟踪
        RunTrace(
            comment_id=event.comment_id,
            post_id=event.post_id,
            trace_id=current_trace_id(),
            event_id=current_event_id() or event.event_id,
            trigger_content=event.content,
            decision=outcome.decision,
            mode=outcome.mode,
            reason=outcome.reason,
            context_text=outcome.context_text,
            generated_content=outcome.generated_content,
            prompt_tokens=outcome.prompt_tokens,
            completion_tokens=outcome.completion_tokens,
            generation_usage_complete=outcome.generation_usage_complete,
            generation_validation_failed=outcome.generation_validation_failed,
            cost_li=outcome.cost_li,
            daily_cost_li_after=outcome.daily_cost_li_after,
            error=outcome.error,
            truncations=outcome.truncations,
            memory_selected_ids=outcome.memory_selected_ids,
            persona_version=outcome.persona_version,
            retrieval_degraded=outcome.retrieval_degraded,
            leak_hits=outcome.leak_hits,
            memory_degraded=outcome.memory_degraded,
            cost_tier=outcome.cost_tier,
            light_model_used=outcome.light_model_used,
            duration_ms=duration_ms,
            stage_ms=outcome.stage_ms,
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
    """结算本次调用过的依赖；域内任一关键调用失败即记一次失败。"""
    health = _RunHealth()
    try:
        outcome = await _execute_inner(event, deps, health)
        outcome.stage_ms = health.stage_ms
        return outcome
    finally:
        for domain in ("llm", "memory", "main_service"):
            if getattr(health, f"{domain}_used"):
                breaker = getattr(deps.breakers, domain)
                if getattr(health, f"{domain}_failed"):
                    breaker.record_failure()
                else:
                    breaker.record_success()


async def _execute_inner(event: TriggerEvent, deps: PipelineDeps, health: _RunHealth) -> _Outcome:
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
    # ②.5 灰度：空名单全量，非空名单仅放行指定用户
    graylist = deps.control_plane.snapshot.graylist
    if graylist and event.commenter_user_id not in graylist:
        return _Outcome("skipped_graylist", "灰度名单未放行该用户")
    # ②.6 尝试配额：同帖共享；超限尝试也刷新静默期，存储故障fail-open
    try:
        allowed, rate_reason = await rate_limit.check_and_count(
            deps.kv,
            post_id=event.post_id,
            user_id=event.commenter_user_id,
            post_limit=deps.post_reply_limit,
            post_window_hours=deps.post_rate_window_hours,
            user_daily_limit=deps.user_daily_limit,
        )
    except Exception as exc:
        logger.warning("频率键读写失败，放行（fail-open）：%s", exc)
        allowed, rate_reason = True, ""
    if not allowed:
        return _Outcome("skipped_rate_limit", rate_reason)
    # ③ 审核预检（红线：违规一律不出）
    verdict = await moderation.precheck(event.content)
    if not verdict.passed:
        return _Outcome("rejected_moderation", verdict.reason)
    # ④ 硬规则低价值过滤（零成本，拉取前拦截——§5.6 ①）
    low_value_reason = decision.hard_low_value(event.content)
    if low_value_reason is not None:
        return _Outcome("skipped_low_value", low_value_reason)

    # ④.5 成本读档（M5：所有 LLM 调用前判档；读失败 fail-open 按充足——成本是预算控制非安全红线）
    cost_tier: budget.CostTier = "sufficient"
    cost_day = datetime.now(UTC).date()  # 本 run 判档与累加使用同一 UTC 日键。
    try:
        daily_cost_li = await budget.read_cost(deps.kv, cost_day)
        cost_tier = budget.classify_tier(
            daily_cost_li, deps.cost_tight_threshold_li, deps.cost_exhausted_threshold_li
        )
    except Exception as exc:
        logger.warning("成本键读取失败，按充足档继续（fail-open，事后对账可见）：%s", exc)
    if cost_tier == "exhausted":
        return _Outcome(
            "skipped_cost_exhausted",
            "当日成本已到枯竭阈值，规则兜底不调模型",
            cost_tier=cost_tier,
        )

    decision_result: decision.DecisionResult | None = (
        None  # failed 归因锚（决策成功后链路炸时携带 mode）
    )
    leak_hits: tuple[str, ...] = ()
    # ⑤ 拉取现场（M5：main_service 熔断前置判定——open=failed 静默，写库=审核入口承接 PRD"审核"档）
    try:
        deps.breakers.main_service.before_call()
    except BreakerOpenError:
        return _Outcome("failed", "主服务熔断 open（连续失败≥阈值），静默不回", cost_tier=cost_tier)
    memory_degraded = False  # M5：记忆降级留痕（open 或异常降级均置位）
    memory_allowed = True
    cost_li: int | None = None
    cost_after: int | None = None
    light_model_used = False
    generation_input_price = deps.llm_input_price_per_mtok
    generation_output_price = deps.llm_output_price_per_mtok
    generation_attempted = False
    generation_usage_complete: bool | None = None
    health.main_service_used = True
    try:
        # ⑤ 拉取现场（C-2①③ 线程 + C-2② 全量楼层）
        thread = await deps.comment_tree.fetch_context(event)
        floors = context.filter_floors_at_waterline(
            event,
            thread,
            await deps.comment_tree.fetch_floors(event.post_id),
        )  # ⑤ 水位过滤：触发后的楼层不得进入本次决策
        # ⑥ 记忆粗召回（向量管"找得到"；负面 feedback 全量并行取）——记忆是可降级通道：
        # M5：memory 熔断 open=降级空候选不计数；异常失败=降级+计失败（真存储故障不阻断回复链路）
        persona_version = (
            deps.control_plane.snapshot.persona_version or deps.persona.persona_version
        )
        try:
            deps.breakers.memory.before_call()
            health.memory_used = True
            candidates = await deps.memory_store.recall(
                event.commenter_user_id, persona_version, event.content, deps.memory_recall_top_k
            )
            negatives = await deps.memory_store.negative_feedback(
                event.commenter_user_id, persona_version
            )
        except BreakerOpenError:
            logger.warning("记忆熔断 open，降级为空候选（不阻断回复）")
            candidates, negatives = (), ()
            memory_degraded = True
            memory_allowed = False
        except Exception as exc:  # 记忆读失败降级（M5 起计入熔断计数）
            logger.warning("记忆召回失败降级为空候选（不阻断回复）：%s", exc)
            candidates, negatives = (), ()
            memory_degraded = True
            health.memory_failed = True
        # ⑦ 决策一车四用（轻量调用收口；失败=failed 静默；M5：llm 熔断前置判定——open=failed_breaker）
        try:
            deps.breakers.llm.before_call()
        except BreakerOpenError:
            return _Outcome(
                "failed_breaker",
                "LLM 熔断 open（连续失败≥阈值），静默不回",
                memory_degraded=memory_degraded,
                cost_tier=cost_tier,
            )
        nearby_digest = "\n".join(f"{node.user_id}：{node.content}" for node in thread.chain)
        health.llm_used = True
        decision_result = await _timed(
            health.stage_ms,
            "decision",
            decision.decide(event, deps.llm, thread.post.content[:500], nearby_digest, candidates),
        )
        if not decision_result.should_reply:
            return _Outcome(
                "skipped_decision",
                decision_result.reason,
                mode=decision_result.mode,
                cost_tier=cost_tier,
                memory_degraded=memory_degraded,
                persona_version=persona_version,
            )
        # ⑧ 检索（need_retrieval 且检索可用；不可用降级留痕照常回复——场景 6 降级链路）。
        # 片段渲染行注入预留区（Task 13 接线）：渲染以 RESERVED_BUDGET 为软上界，
        # 超限丢末位片段并 TruncationRecord 留痕（病态长片段不挤爆预留区逼出末兜底切头部）
        retrieval_degraded = False
        retrieval_lines: list[str] = []
        retrieval_trunc: tuple[TruncationRecord, ...] = ()
        if decision_result.need_retrieval:
            if deps.retriever is None or not memory_allowed:
                retrieval_degraded = True  # 检索未配置：降级直说不知道（生成侧 prompt 已含）
            else:
                try:
                    fragments = await deps.retriever.retrieve(
                        event.content, limit=deps.rag_fragment_limit
                    )
                except Exception as exc:  # 检索失败=降级不阻断（与未配置同口径，熔断归 M5）
                    logger.warning("RAG 检索失败降级（不阻断回复）：%s", exc)
                    retrieval_degraded = True
                    memory_degraded = True
                    health.memory_failed = True
                else:
                    for fragment in fragments:  # 渲染行（来源标注随行——trace 对质用）
                        line = f"【检索|{fragment.doc_kind}】{fragment.content}（来源：{fragment.source}）"
                        if len("\n".join([*retrieval_lines, line])) > RESERVED_BUDGET:
                            retrieval_trunc = (
                                *retrieval_trunc,
                                TruncationRecord(
                                    channel="B",
                                    what=f"检索片段（来源：{fragment.source}）",
                                    reason="超预留预算丢末位",
                                    chars_dropped=len(line),
                                ),
                            )
                            break
                        retrieval_lines.append(line)
        # ⑨ 四通道总装（B/C/D+预留；截断全部留痕；AI 历史区=C-2③ bot_history ∪ 对话链去重）
        selected = [
            record for record in candidates if record.memory_id in decision_result.memory_selection
        ]
        memory_text, memory_trunc = user_memory.render_memory_block(
            selected, negatives, deps.memory_select_max
        )
        # 同帖多轮（TTL 窗口内）——对话链是 C-2③ bot_history 的兜底数据源，读失败降级空链不阻断
        try:
            dialogue_turns = (
                await dialogue.read_chain(deps.kv, event.post_id) if memory_allowed else ()
            )
        except Exception as exc:
            logger.warning("对话级记忆 read_chain 失败（降级空链，不阻断回复）：%s", exc)
            dialogue_turns = ()
            health.memory_failed = True
            memory_degraded = True
        assembled = await _timed(
            health.stage_ms,
            "context",
            context.assemble(
                event,
                thread,
                floors,
                memory_text,
                memory_trunc,
                deps.summarizer,
                deps.kv,
                persona_version,
                deps.summary_cache_ttl_hours,
                retrieval_lines=retrieval_lines,
                extra_history_lines=_dialogue_history_lines(thread, dialogue_turns),
            ),
        )
        # ⑩ 生成（M5：吃紧档仅切生成——决策/摘要保持主模型保判断质量；轻模型未配置回落主模型）
        generation_llm = deps.llm
        light_model_used = False
        if cost_tier == "tight":
            if deps.llm_light is not None:
                generation_llm = deps.llm_light
                generation_input_price = deps.light_llm_input_price_per_mtok
                generation_output_price = deps.light_llm_output_price_per_mtok
                light_model_used = True
                logger.warning("成本吃紧档：生成切换轻模型")
            else:
                logger.warning("成本吃紧但轻模型未配置，生成沿用主模型")
        generation_attempted = True
        output = await _timed(
            health.stage_ms,
            "generation",
            generation.generate(
                event, decision_result, generation_llm, assembled.user_text, deps.persona
            ),
        )
        generation_usage_complete = output.usage_complete
        cost_li = budget.estimate_cost_li(
            output.prompt_tokens,
            output.completion_tokens,
            generation_input_price,
            generation_output_price,  # 按实际调用模型的单价折算（轻模型按自身价）
        )
        try:
            cost_after = await budget.add_cost(deps.kv, cost_li, cost_day, deps.cost_key_ttl_hours)
        except Exception as exc:  # M5：记账失败不吞回复（对账缺口事后可见）
            logger.warning("成本累加失败（回复照常，对账可见缺口）：%s", exc)
            cost_after = None
        # ⑪ 泄漏扫描（第三道审核，写库前）→ 命中替换但不断流
        sanitized = leak_scan.sanitize(output.reply.content, deps.leak_extra_patterns)
        if sanitized.hits:
            logger.warning("输出泄漏扫描命中（已脱敏）：%s", sanitized.hits)
        output.reply.content = sanitized.content
        leak_hits = sanitized.hits
        await _timed(health.stage_ms, "write", deps.reply_writer.write_reply(output.reply))
    except (CommentFetchError, LLMClientError, ReplyWriteError) as exc:
        # 已知失败类型静默不回（红线 §0.3）；决策已成功时携带 mode 归因（M2 口径保持）。
        # 2026-09-17 review I-1：拉取异常原裸逃 _execute 击穿 run() 单出口（无 RunTrace/
        # 无归因，consumer 兜底丢观测）——HTTPCommentTreeFetcher 现统一包装 CommentFetchError。
        if isinstance(exc, LLMClientError):  # M5：LLM 域失败（决策/生成任一）
            health.llm_failed = True
            if generation_attempted:
                generation_usage_complete = getattr(exc, "usage_complete", False)
                known_prompt_tokens = getattr(exc, "prompt_tokens", None)
                known_completion_tokens = getattr(exc, "completion_tokens", None)
                if known_prompt_tokens is not None and known_completion_tokens is not None:
                    cost_li = budget.estimate_cost_li(
                        known_prompt_tokens,
                        known_completion_tokens,
                        generation_input_price,
                        generation_output_price,
                    )
                    try:
                        cost_after = await budget.add_cost(
                            deps.kv, cost_li, cost_day, deps.cost_key_ttl_hours
                        )
                    except Exception as cost_exc:  # 成本记账失败仍保持静默失败，但留痕
                        logger.warning("失败生成成本累加失败（对账可见缺口）：%s", cost_exc)
                        cost_after = None
        else:  # M5：拉取/写库域失败（main_service 承接）
            health.main_service_failed = True
        logger.warning(
            "bot_pipeline_dependency_failed commentId=%s exceptionType=%s stageMs=%s",
            event.comment_id,
            type(exc).__name__,
            health.stage_ms,
        )
        return _Outcome(
            "failed",
            f"链路异常静默不回：{exc}",
            mode=decision_result.mode if decision_result is not None else None,
            error=str(exc),
            leak_hits=leak_hits,
            memory_degraded=memory_degraded,
            cost_tier=cost_tier,
            prompt_tokens=(
                getattr(exc, "prompt_tokens", None)
                if isinstance(exc, LLMClientError) and generation_attempted
                else None
            ),
            completion_tokens=(
                getattr(exc, "completion_tokens", None)
                if isinstance(exc, LLMClientError) and generation_attempted
                else None
            ),
            generation_usage_complete=generation_usage_complete,
            generation_validation_failed=(
                getattr(exc, "validation_failed", False)
                if isinstance(exc, LLMClientError) and generation_attempted
                else False
            ),
            cost_li=cost_li,
            daily_cost_li_after=cost_after,
            light_model_used=light_model_used,
        )

    # ⑫ replied 后：记忆四态落库 + 对话链 append（失败 WARNING 不阻断——回复已成功）
    if decision_result.memory_ops and memory_allowed:
        try:
            await _apply_memory_ops(
                deps.memory_store,
                event.commenter_user_id,
                persona_version,
                decision_result.memory_ops,
                event.event_id,
            )
        except Exception as exc:  # 记忆失败不回滚回复（观测 WARNING；M5 起计入熔断计数）
            logger.warning("记忆四态落库失败（不阻断回复）：%s", exc)
            health.memory_failed = True
            memory_degraded = True
    try:
        if memory_allowed:
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
        health.memory_failed = True
        memory_degraded = True

    return _Outcome(
        "replied",
        f"链路完整（决策：{decision_result.reason}；模式 {decision_result.mode}；"
        f"本次成本 {cost_li} 厘，"
        f"日累计 {cost_after if cost_after is not None else '记账失败'} 厘）",
        mode=decision_result.mode,
        context_text=assembled.user_text,
        generated_content=output.reply.content,
        prompt_tokens=output.prompt_tokens,
        completion_tokens=output.completion_tokens,
        generation_usage_complete=generation_usage_complete,
        cost_li=cost_li,
        daily_cost_li_after=cost_after,
        truncations=(*assembled.truncations, *retrieval_trunc),
        memory_selected_ids=tuple(decision_result.memory_selection),
        persona_version=persona_version,
        retrieval_degraded=retrieval_degraded,
        leak_hits=leak_hits,
        memory_degraded=memory_degraded,
        cost_tier=cost_tier,
        light_model_used=light_model_used,
    )
