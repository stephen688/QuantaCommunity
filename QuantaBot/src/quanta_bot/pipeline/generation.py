"""pipeline/generation —— 生成层（M2：经 LLMClient 真调；M3：人格内核+四模式 prompt）。

职责：组装 system+user 调 LLM；强制 AI 身份标识（红线 §0.1）；输出回复与 usage（成本折算输入）。
边界：人格 prompt 由 persona.py 读 prompts/ 数据文件（M3 起）；
      不折算成本（budget/crosscutting 负责）；LLM 失败不在此捕获（管线 failed 分支统一处理）。
"""

from __future__ import annotations

from typing import TYPE_CHECKING

from pydantic import BaseModel

from quanta_bot.pipeline.persona import PersonaLibrary
from quanta_bot.pipeline.trigger import TriggerEvent

if TYPE_CHECKING:
    from quanta_bot.pipeline.decision import (
        DecisionResult,
    )  # 仅注解用途——模块级导入会与 decision→ports 成环
    from quanta_bot.pipeline.ports import LLMClient

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
    persona: PersonaLibrary,
) -> GenerationOutput:
    """按上下文调 LLM 生成回复（decision.mode 驱动人格模式，无决策默认生活玩梗）。

    user_prompt=assemble 产物原样——触发评论已由 assemble 触发行承载（红线）且受
    12000 预算校验；M2 遗留的触发评论后缀曾致三重注入且在校验后追加可超预算（已删）。
    """
    user_prompt = context_text
    system = persona.system_prompt(
        decision.mode if decision else "生活玩梗"
    )  # 人格 system（A 通道）
    result = await llm.complete(system=system, user=user_prompt)  # 调用 LLM
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
