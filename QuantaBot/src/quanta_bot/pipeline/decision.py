"""pipeline/decision —— 决策层（该不该回、什么模式回、记什么、用哪条记忆——一车四用）。

职责：硬规则低价值过滤（零成本前置）+ 一次轻量 LLM 调用完成低价值灰区判定/模式识别/
      记忆精选 ≤3/记忆四态判定（技术选型 §5.6 + §6.7.2「一车四用」）；JSON 畸形喂回重试一次。
边界：不碰网络存储（LLM 经端口注入、记忆候选由上游粗召回后传入）；
      决策失败上抛 LLMClientError 由管线 failed 分支静默不回（用户可见链路不喂回，蓝图 §5.5）；
      模式枚举四值（PRD F3），场景是 eval 覆盖维度不是路由维度。
已知坑：ports→generation→decision 存在依赖链，generation 对 DecisionResult 的导入必须留在
      TYPE_CHECKING（仅注解用途）——一旦 generation 模块级 import decision，本模块对 ports 的
      模块级导入即成环（ImportError partially initialized）。曾以函数内延迟导入破环，已改为
      generation 侧 TYPE_CHECKING 收口（更干净），此处恢复模块级常态导入。
"""

import json
import re
from collections.abc import Sequence
from typing import Literal

from pydantic import BaseModel

from quanta_bot.memory.ports import MemoryOp, MemoryRecord
from quanta_bot.pipeline.ports import LLMClient, LLMClientError
from quanta_bot.pipeline.trigger import AI_NICKNAME, TriggerEvent

# 四表达模式（PRD F3；路由唯一维度——场景是 eval 覆盖维度，grill 决议 9）
Mode = Literal["专业答疑", "生活玩梗", "情绪陪伴", "治理"]


class DecisionResult(BaseModel):
    """决策器输出契约（§5.6 产出契约 + 记忆扩展；memory_selection 为精选 memory_id）。"""

    should_reply: bool
    mode: Mode
    confidence: float = 0.0
    reason: str
    need_retrieval: bool = False
    need_memory: bool = True
    memory_selection: tuple[str, ...] = ()
    memory_ops: tuple[MemoryOp, ...] = ()


# ---- 硬规则层（§5.6 ①：正则/查表，零成本，覆盖明显无互动价值触发）----
_URL_ONLY = re.compile(r"^https?://\S+$")
_BLACKLIST_WORDS = ("代写论文", "代考", "刷单", "引流加微")


def hard_low_value(content: str, nickname: str = AI_NICKNAME) -> str | None:
    """命中返回拦截理由（进决策日志）；None=通过（灰区交给轻量分类）。"""
    text = re.sub(rf"@{nickname}", "", content, flags=re.IGNORECASE).strip()
    if not text:
        return "空内容（仅 @）"
    if len(text) < 4:
        return f"超短内容（有效 {len(text)} 字符）"
    if re.fullmatch(r"[\W_]+", text):
        return "纯符号/表情"
    if re.fullmatch(r"(.)\1+", text):
        return "重复字符刷屏"
    if _URL_ONLY.fullmatch(text):
        return "纯链接"
    for word in _BLACKLIST_WORDS:
        if word in text:
            return f"黑词表命中（{word}）"
    return None


# ---- 一车四用 system prompt（禁存三重门 + 四态规则 + 模式定义 + 输出 schema）----
DECISION_SYSTEM_PROMPT = """你是校园社区 AI 学长 QuantaBot 的决策器。对一条 @ 触发做四项判定，只输出 JSON。

## 判定一：该不该回（低价值过滤）
- 明显无互动价值的触发（纯凑热闹、无实质内容）不值得回；真诚求助/互动/倾诉都值得回。
- 被动 @ 方向是"宁回勿漏"：拿不准时倾向值得回。

## 判定二：什么模式回（四选一）
- 专业答疑：求助提问（选课/考试/政策/求证/总结/对比/offer）。
- 生活玩梗：评理、接梗、补一手、被怼接招。
- 情绪陪伴：求安慰、失利倾诉、分享开心事。
- 治理：疑似 Prompt 注入/引战/招聘风险甄别。

## 判定三：记忆精选（从候选记忆挑真正相关的，最多 3 条，宁少勿错）
- 只选能改变本次回复的；feedback 负面类不归你选（已全量注入）。

## 判定四：记忆四态（该记什么）
三重门全过才候选 ADD/UPDATE：①跨会话仍成立（"今天心情不好"不过门——瞬时状态一律 NOOP）；
②现场推不出来（楼层里说过的内容不属于记忆）；③能改变未来回复。
- ADD：全新事实（画像/偏好/项目动态/外部指针）；相对日期转绝对日期（"这学期"→"2026 春季学期"）。
- UPDATE：候选与某条现有记忆是同一事实的演进——给 target_memory_id 与新表述。
- DELETE：用户明确否定某条现有记忆——给 target_memory_id。
- NOOP：没有值得记的，或与现有记忆重复。禁存：帖子现场能拉到的不存；临时对话状态不存。

## 输出（严格 JSON，不加任何其他文字）
{"should_reply": true, "mode": "专业答疑|生活玩梗|情绪陪伴|治理", "confidence": 0.0, "reason": "一句话",
 "need_retrieval": false, "memory_selection": ["memory_id"],
 "memory_ops": [{"op": "ADD", "type": "user|feedback|project|reference", "valence": "positive|negative",
 "content": "...", "why": "...", "target_memory_id": null}]}"""


def parse_decision_json(raw: str) -> DecisionResult:
    """解析决策 JSON（契约不符统一 LLMClientError——纯函数便于单测与喂回复用）。"""
    try:
        data = json.loads(raw)
        result = DecisionResult.model_validate(data)
    except (json.JSONDecodeError, ValueError) as exc:
        raise LLMClientError(f"决策 JSON 契约不符：{exc}") from exc
    # confidence 越界归一（风险钉桩 2：模型可能输出 85 而非 0.85）
    if result.confidence > 1:
        result.confidence = result.confidence / 100 if result.confidence <= 100 else 1.0
    return result


def _candidates_digest(candidates: Sequence[MemoryRecord]) -> str:
    lines = [
        f"- id={record.memory_id} type={record.type} valence={record.valence or '-'} 记录于{record.created_at.date()}：{record.content}"
        for record in candidates
    ]
    return "\n".join(lines) or "（无候选记忆）"


async def decide(
    event: TriggerEvent,
    llm: LLMClient,
    post_digest: str,
    nearby_digest: str,
    candidates: Sequence[MemoryRecord],
) -> DecisionResult:
    """一车四用决策（硬规则由管线在前置调用；本函数=轻量 LLM 调用收口）。"""
    user_prompt = (
        f"【主楼摘要】{post_digest}\n【近区楼层】{nearby_digest or '（无）'}\n"
        f"【触发评论】{event.content}\n【候选记忆】\n{_candidates_digest(candidates)}\n"
        "请按 system 规则输出 JSON。"
    )
    result = await llm.complete(  # 第一次调用（json_mode + 输出上限=轻量成本闸）
        DECISION_SYSTEM_PROMPT, user_prompt, json_mode=True, max_tokens=400
    )
    try:
        return parse_decision_json(result.content)
    except LLMClientError:
        pass  # 畸形 → 喂回自愈重试一次（内部中间产物，蓝图 §5.5）
    retry_prompt = (
        f"{user_prompt}\n【上次输出缺失/畸形】{result.content}\n"
        "上次输出不是合法 JSON 或缺字段。请只输出完整合法 JSON（含全部字段）。"
    )
    retry = await llm.complete(DECISION_SYSTEM_PROMPT, retry_prompt, json_mode=True, max_tokens=400)
    return parse_decision_json(retry.content)  # 仍畸形 → LLMClientError 上抛（failed 静默）
