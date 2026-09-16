"""pipeline/generation —— 生成层（M2：经 LLMClient 真调；M3：人格内核+四模式 prompt）。

职责：组装 system+user 调 LLM；强制 AI 身份标识（红线 §0.1）；输出回复与 usage（成本折算输入）。
边界：不内联人格 prompt 长文（M2 用最小临时 system 提示词，M3 起改读 prompts/ 数据文件）；
      不折算成本（budget/crosscutting 负责）；LLM 失败不在此捕获（管线 failed 分支统一处理）。
"""

from __future__ import annotations

from typing import TYPE_CHECKING

from pydantic import BaseModel

from quanta_bot.pipeline.decision import DecisionResult
from quanta_bot.pipeline.trigger import TriggerEvent

if TYPE_CHECKING:
    from quanta_bot.pipeline.ports import LLMClient

# M2 临时系统提示词（人格 v1 于 M3 以 prompts/ 数据文件落地后替换为读文件）
_SYSTEM_PROMPT = (
    "你是校园社区 QuantaCommunity 的 AI 学长 QuantaBot。必须遵守："
    "1）你是 AI，不是真人，绝不冒充真人校友；"
    "2）回复简短、口语化、友善；"
    "3）只依据给定上下文与常识回答，不确定就直说不知道，不编造（防幻觉护栏）；"
    "4）严禁输出违法违规或伤害性内容。"
)

# AI 身份标识（红线 §0.1——生成层强制注入，不依赖模型自觉）
AI_BADGE = "[QuantaBot·AI 学长]"


class GeneratedReply(BaseModel):
    """生成回复（携带 CommentAddDTO 映射所需全部字段——writer 做最终序列化）。

    [联调校准点] parentId 语义：触发评论为一级评论（parent_id=None）时回复挂其下
    （parent_floor=触发 comment_id）；触发评论为楼内回复时沿用其 parent_id。
    """

    post_id: int  # → contentId
    answer_id: int | None = None  # → answerId（专业区透传触发事件）
    reply_to_comment_id: int  # → replyCommentId（回复锚点=触发评论）
    reply_to_user_id: int  # → replyUserId（触发评论作者）
    parent_floor_comment_id: int  # → parentId（一级楼层）
    content: str


class GenerationOutput(BaseModel):
    """生成结果（回复契约 + usage——写库契约与成本输入分字段，不互相污染）。"""

    reply: GeneratedReply
    prompt_tokens: int = 0
    completion_tokens: int = 0


async def generate(
    event: TriggerEvent,
    decision: DecisionResult | None,
    llm: LLMClient,
    context_text: str,
) -> GenerationOutput:
    """按上下文+触发评论调 LLM 生成回复（decision 为链路形态占位，M3 起驱动模式 prompt）。"""
    user_prompt = f"帖子上下文：\n{context_text}\n\n触发评论：{event.content}"  # 用户提示词
    result = await llm.complete(system=_SYSTEM_PROMPT, user=user_prompt)  # 调用 LLM
    content = result.content.strip()
    if AI_BADGE not in content:
        content = f"{AI_BADGE} {content}"
    parent_floor = event.parent_id if event.parent_id is not None else event.comment_id
    reply = GeneratedReply(
        post_id=event.post_id,
        answer_id=event.answer_id,
        reply_to_comment_id=event.comment_id,
        reply_to_user_id=event.commenter_user_id,
        parent_floor_comment_id=parent_floor,
        content=content,
    )
    return GenerationOutput(
        reply=reply,
        prompt_tokens=result.prompt_tokens,
        completion_tokens=result.completion_tokens,
    )
