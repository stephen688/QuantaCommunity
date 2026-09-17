"""eval runner：YAML 用例 → 自组 fake 管线（persona 档真 LLM）→ deterministic 断言 + Judge。

两档（grill 决议 13）：pipeline 档 FakeLLM 剧本（hermetic，CI 跑）；persona 档 QUANTABOT_EVAL=1
真调 DeepSeek 生成 + Judge（deepseek-chat，自评偏置记录在案，M4 再评估独立 Judge）。
组装口径（2026-09-17 Task 10 派发前澄清）：run_case 组装全部在本文件内实现（不 import
tests/unit 测试代码）；CommentTreeFetcher 为从 case.trigger 构造的内联 fake；
memory_ops 由 SpyMemoryStore 包装捕获；reply/trace 取 FakeReplyWriter/_SpyTracer 末位。
"""

import json
import os
import tempfile
from collections.abc import Callable, Sequence
from datetime import UTC
from pathlib import Path
from typing import Literal

import yaml
from pydantic import BaseModel

from quanta_bot.crosscutting.killswitch import ControlPlane
from quanta_bot.infra.audit_db import SQLiteAudit
from quanta_bot.infra.deepseek import DeepSeekClient, FakeLLM
from quanta_bot.infra.kv import InMemoryKV
from quanta_bot.infra.main_service import FakeReplyWriter
from quanta_bot.infra.settings import Settings
from quanta_bot.memory.ports import HashEmbeddingClient, MemoryOp, MemoryRecord
from quanta_bot.memory.user_memory import InMemoryUserMemoryStore
from quanta_bot.pipeline import pipeline
from quanta_bot.pipeline.context import LLMSummarizer
from quanta_bot.pipeline.persona import PersonaLibrary
from quanta_bot.pipeline.pipeline import PipelineDeps
from quanta_bot.pipeline.ports import CommentNode, LLMClient, PostSummary, PostThread, RunTrace
from quanta_bot.pipeline.trigger import TriggerEvent

CASES_DIR = Path(__file__).resolve().parents[2] / "eval" / "cases"
EVAL_PERSONA_ENABLED = os.getenv("QUANTABOT_EVAL", "") == "1"


class EvalCase(BaseModel):
    """用例契约（YAML 直载；memories 预置进 InMemoryUserMemoryStore——persona_version 不写死，
    由 _stamp_memories 以运行时人格版本盖章）。
    """

    id: str
    scenario: int
    scenario_name: str
    mode: str
    tier: Literal["pipeline", "persona"]
    memories: list[dict] = []
    trigger: dict
    llm_script: list[str] | None = None
    deterministic: list[dict] = []
    judge: dict | None = None


class CaseResult(BaseModel):
    """用例执行结果（deterministic 断言与 Judge 的输入）。"""

    decision: str
    reply: str
    trace: RunTrace | None = None
    memory_ops: tuple[MemoryOp, ...] = ()


def load_case(path: Path) -> EvalCase:
    """YAML → EvalCase（schema 不符=用例本身写错，让它炸出来）。"""
    return EvalCase.model_validate(yaml.safe_load(path.read_text(encoding="utf-8")))


def _stamp_memories(memories: list[dict], persona_version: str) -> list[MemoryRecord]:
    """预置记忆版本改写：YAML 不写死 hash（人格审定后 hash 变化不破坏用例），
    runner 统一以运行时 PersonaLibrary().persona_version 盖章（契约测试覆盖；写了也忽略）。
    兼容修正：YAML 日期串（如 "2026-06-01"）解析为 naive datetime，而生产渲染的
    _days_ago 用 aware now 做减法——此处统一补 UTC 时区（用例书写保持日期串自然形态）。
    """
    records: list[MemoryRecord] = []
    for memo in memories:
        record = MemoryRecord.model_validate({**memo, "persona_version": persona_version})
        if record.created_at.tzinfo is None:
            record.created_at = record.created_at.replace(tzinfo=UTC)
        records.append(record)
    return records


class _CaseCommentTreeFetcher:
    """从 case.trigger 构造的内联 fake 评论树（fetch_context=主楼+触发评论父链线程，
    fetch_floors=楼层元组——camelCase 字段名经 Pydantic alias 映射为 snake_case）。
    """

    def __init__(self, trigger: dict) -> None:
        self._thread = PostThread(
            post=PostSummary.model_validate(trigger["post"]),
            chain=(CommentNode.model_validate(trigger["comment"]),),  # 触发评论即父链（无楼中楼）
        )
        self._floors = tuple(
            CommentNode.model_validate(floor) for floor in trigger.get("floors") or ()
        )

    async def fetch_context(self, event: TriggerEvent) -> PostThread:
        """返回用例线程（event 仅对齐端口签名——fake 单帖数据）。"""
        return self._thread

    async def fetch_floors(self, post_id: int) -> tuple[CommentNode, ...]:
        """返回用例楼层（post_id 仅对齐端口签名）。"""
        return self._floors


class SpyMemoryStore:
    """记忆库间谍：包装 InMemoryUserMemoryStore——apply_ops 先记录 ops 再转发
    （memory_op_is 对 UPDATE/DELETE/ADD/NOOP 集合断言的捕获依据）。
    """

    def __init__(self, inner: InMemoryUserMemoryStore) -> None:
        self._inner = inner
        self.captured_ops: list[MemoryOp] = []

    def preset(self, records: Sequence[MemoryRecord]) -> None:
        """预置记忆注入（沿用 Task 9 测试的 _records 直注口径——fake 存储无公开预置 API）。"""
        self._inner._records.extend(records)

    async def recall(
        self, user_id: int, persona_version: str, query_text: str, limit: int
    ) -> tuple[MemoryRecord, ...]:
        return await self._inner.recall(user_id, persona_version, query_text, limit)

    async def negative_feedback(
        self, user_id: int, persona_version: str
    ) -> tuple[MemoryRecord, ...]:
        return await self._inner.negative_feedback(user_id, persona_version)

    async def apply_ops(self, user_id: int, persona_version: str, ops: Sequence[MemoryOp]) -> None:
        self.captured_ops.extend(ops)
        await self._inner.apply_ops(user_id, persona_version, ops)


class _SpyTracer:
    """trace 间谍（CaseResult.trace 捕获口径——不 mock 业务，只观测）。"""

    def __init__(self) -> None:
        self.traces: list[RunTrace] = []

    async def record(self, trace: RunTrace) -> None:
        self.traces.append(trace)


def trigger_event_from(case: EvalCase) -> TriggerEvent:
    """trigger.comment → TriggerEvent（commentId/userId/content 三键映射；mentioned_bot 恒 True）。"""
    comment = case.trigger["comment"]
    return TriggerEvent(
        event_id=f"eval-{case.id}-{comment['commentId']}",
        comment_id=comment["commentId"],
        post_id=case.trigger["post"]["postId"],
        commenter_user_id=comment["userId"],
        content=comment["content"],
        mentioned_bot=True,
    )


def build_case_deps(
    case: EvalCase, llm: LLMClient
) -> tuple[PipelineDeps, FakeReplyWriter, _SpyTracer, SpyMemoryStore]:
    """生产组件自组管线依赖（复用 Task 9 _m3_deps 形态：PersonaLibrary / InMemoryUserMemoryStore /
    LLMSummarizer / InMemoryKV / SQLiteAudit(tempfile) / FakeReplyWriter / ControlPlane；
    评论树=case.trigger 内联 fake）。
    """
    kv = InMemoryKV()
    writer = FakeReplyWriter()
    tracer = _SpyTracer()
    spy_store = SpyMemoryStore(InMemoryUserMemoryStore(HashEmbeddingClient()))
    deps = PipelineDeps(
        kv=kv,
        audit=SQLiteAudit(str(Path(tempfile.mkdtemp(prefix="quantabot-eval-")) / "eval.db")),
        reply_writer=writer,
        llm=llm,
        tracer=tracer,
        control_plane=ControlPlane(kv),
        comment_tree=_CaseCommentTreeFetcher(case.trigger),
        persona=PersonaLibrary(),
        memory_store=spy_store,
        summarizer=LLMSummarizer(llm),
    )
    return deps, writer, tracer, spy_store


async def run_case(case: EvalCase) -> CaseResult:
    """跑一条用例：fake 依赖自组（persona 档 llm=真 DeepSeekClient，Settings 读 .env）。"""
    settings = Settings()
    llm: LLMClient = (
        DeepSeekClient(
            settings.deepseek_base_url,
            settings.deepseek_api_key,
            settings.deepseek_model,
            settings.llm_timeout_seconds,
        )
        if case.tier == "persona" and EVAL_PERSONA_ENABLED
        else FakeLLM(responses=case.llm_script)
    )
    deps, writer, tracer, spy_store = build_case_deps(case, llm)
    spy_store.preset(_stamp_memories(case.memories, deps.persona.persona_version))
    try:
        decision = await pipeline.run(trigger_event_from(case), deps)
    finally:
        if isinstance(llm, DeepSeekClient):
            await llm.aclose()  # persona 档真连接收尾（pipeline 档 FakeLLM 无连接可关）
    return CaseResult(
        decision=decision,
        reply=writer.written[-1].content if writer.written else "",
        trace=tracer.traces[-1] if tracer.traces else None,
        memory_ops=tuple(spy_store.captured_ops),
    )


# ---- deterministic 断言函数集（每类一个纯函数；expected 形态随断言类型）----
_MD_LIST_PREFIXES = ("- ", "* ", "1. ", "**", "#")


def _mode_is(expected: str, result: CaseResult) -> bool:
    return result.trace is not None and result.trace.mode == expected


def _decision_is(expected: str, result: CaseResult) -> bool:
    return result.decision == expected


def _reply_contains(expected: str, result: CaseResult) -> bool:
    return expected in result.reply


def _reply_not_contains(expected: list[str], result: CaseResult) -> bool:
    return all(word not in result.reply for word in expected)


def _reply_no_markdown_list(expected: None, result: CaseResult) -> bool:
    """反格式化红线：任一行行首（允许缩进）出现列表/加粗/标题记号即不过。"""
    return not any(
        line.lstrip().startswith(prefix)
        for line in result.reply.splitlines()
        for prefix in _MD_LIST_PREFIXES
    )


def _reply_questions_at_most(expected: int, result: CaseResult) -> bool:
    """问句上限：中文问号+英文问号计数 ≤ expected（防审问式连珠炮）。"""
    return result.reply.count("？") + result.reply.count("?") <= expected


def _memory_op_is(expected: list[str], result: CaseResult) -> bool:
    """记忆四态集合断言：捕获的 op 集合与 expected 完全一致。"""
    return {op.op for op in result.memory_ops} == set(expected)


def _truncation_channel_dropped(expected: str, result: CaseResult) -> bool:
    """截断留痕在场断言：trace.truncations 存在指定通道（B/C/D）的记录。"""
    return result.trace is not None and any(
        record.channel == expected for record in result.trace.truncations
    )


def _decay_warning_present(expected: bool, result: CaseResult) -> bool:
    """时间衰减警告在场判定：context_text 同时含「记录于」与「可能过时」。"""
    text = result.trace.context_text if result.trace is not None else ""
    return ("记录于" in text and "可能过时" in text) == expected


def _memory_selected_count(expected: int, result: CaseResult) -> bool:
    return result.trace is not None and len(result.trace.memory_selected_ids) == expected


_ASSERTIONS: dict[str, Callable[[object, CaseResult], bool]] = {
    "mode_is": _mode_is,
    "decision_is": _decision_is,
    "reply_contains": _reply_contains,
    "reply_not_contains": _reply_not_contains,
    "reply_no_markdown_list": _reply_no_markdown_list,
    "reply_questions_at_most": _reply_questions_at_most,
    "memory_op_is": _memory_op_is,
    "truncation_channel_dropped": _truncation_channel_dropped,
    "decay_warning_present": _decay_warning_present,
    "memory_selected_count": _memory_selected_count,
}


def verify_case(case: EvalCase, result: CaseResult) -> list[str]:
    """deterministic 断言（每类一个小函数；失败信息聚合返回，空=全过）。"""
    failures: list[str] = []
    for spec in case.deterministic:
        check = _ASSERTIONS[spec["assert"]]
        if not check(spec.get("expected"), result):
            failures.append(f"{spec['assert']}(expected={spec.get('expected')}) 未通过")
    return failures


async def judge_case(case: EvalCase, result: CaseResult, llm: LLMClient | None = None) -> list[str]:
    """Judge（persona 档）：P0/P1/P2 三级，deepseek-chat 自评 + JSON 输出；返回失败描述（空=全过）。

    llm 注入口供契约测试（MockTransport/静态 fake）；None=生产自建 DeepSeekClient。
    容错（2026-09-17 真跑实证）：推理模型思考计入 max_tokens，输出可为空/半截——
    解析失败返回失败描述而非抛异常（异常会掩盖 deterministic 断言结果）。
    """
    if not case.judge:
        return []
    owns_client = llm is None
    if llm is None:
        settings = Settings()  # 读 .env（QUANTABOT_EVAL=1 时管理员本地已配 key）
        llm = DeepSeekClient(
            settings.deepseek_base_url,
            settings.deepseek_api_key,
            settings.deepseek_model,
            settings.llm_timeout_seconds,
        )
    try:
        prompt = (
            f"你是社区 AI 回复的评测裁判。逐级判定，只输出 JSON。\n"
            f"【触发情境】帖子：{case.trigger['post']} 评论：{case.trigger['comment']}\n"
            f"【AI 回复】{result.reply}\n"
            f"【评判要点】P0：{case.judge['p0']} P1：{case.judge['p1']} P2：{case.judge['p2']}\n"
            '输出：{"p0": {"pass": true, "reason": "..."}, "p1": {...}, "p2": {...}}'
        )
        raw = await llm.complete(
            "你是严格的评测裁判，按要点逐级判定，只输出 JSON。",
            prompt,
            json_mode=True,
            max_tokens=2000,  # 推理模型思考计入上限（300 曾被烧穿出空内容，实证修正）
        )
        try:
            verdict = json.loads(raw.content)
        except json.JSONDecodeError:
            return [
                f"Judge 输出无法解析（空/截断 JSON，max_tokens 可能不足）：{raw.content[:80]!r}"
            ]
        if not isinstance(verdict, dict):
            return [f"Judge 输出非 object（{type(verdict).__name__}）：{raw.content[:80]!r}"]
        return [
            f"{level}：{verdict.get(level, {}).get('reason', '未判定')}"
            for level in ("p0", "p1", "p2")
            if not verdict.get(level, {}).get("pass", False)
        ]
    finally:
        if owns_client:
            await llm.aclose()
