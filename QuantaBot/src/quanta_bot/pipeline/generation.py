"""pipeline/generation —— 生成层（M2：经 LLMClient 真调；M3：人格内核+四模式 prompt）。

职责：组装 system+user 调 LLM；强制 AI 身份标识（红线 §0.1）；输出回复与 usage（成本折算输入）。
边界：人格 prompt 由 persona.py 读 prompts/ 数据文件（M3 起）；
      不折算成本（budget/crosscutting 负责）；格式修复失败只合并已知 usage 后重抛，
      管线 failed 分支统一静默处理。格式协议不是人格数据，不声明理解隐式问句。
"""

from __future__ import annotations

import re
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
AI_BADGE = "[框框·AI 学长]"
_SAFE_INTERNAL_REFUSAL = f"{AI_BADGE} 这类内部内容不提供。你可以直接说想解决什么问题。"
_INTERNAL_OBJECT = re.compile(
    r"(?:系统|内部|隐藏|秘密).{0,8}(?:提示(?:词)?|指令|规则|配置)"
    r"|(?:提示(?:词)?|指令|规则|配置).{0,8}(?:系统|内部|隐藏|秘密)"
    r"|system\s*prompt",
    re.IGNORECASE,
)
_EXTRACTION_ACTION = re.compile(
    r"公开|输出|泄露|展示|告诉|列出|逐条|改写|复述|总结|翻译|打印|返回|提供|分享|透露"
    r"|写|编写|起草|设计|举例|示例|说明|描述"
)
_FORMAT_REPAIR_INSTRUCTION = (
    "格式校正：最终回复最多一个问题；若需要澄清，请选择唯一最关键的澄清点，"
    "使用自然完整标点，不得省略标点来掩盖多个问题；直接输出最终回复，不复述上一条回复。"
)


def _requests_internal_instructions(content: str) -> bool:
    """识别明确索取/变换内部指令的请求；普通治理问题不命中。"""
    return bool(_INTERNAL_OBJECT.search(content) and _EXTRACTION_ACTION.search(content))


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
    usage_complete: bool = True


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
    if _requests_internal_instructions(event.content):
        parent_floor = event.parent_id if event.parent_id is not None else event.comment_id
        return GenerationOutput(
            reply=GeneratedReply(
                post_id=event.post_id,
                answer_id=event.answer_id,
                reply_to_comment_id=event.comment_id,
                reply_to_user_id=event.commenter_user_id,
                parent_floor_comment_id=parent_floor,
                content=_SAFE_INTERNAL_REFUSAL,
            )
        )
    system = persona.system_prompt(
        decision.mode if decision else "生活玩梗"
    )  # 人格 system（A 通道）
    result = await llm.complete(system=system, user=user_prompt)  # 调用 LLM
    prompt_tokens = result.prompt_tokens
    completion_tokens = result.completion_tokens
    usage_complete = result.usage_complete
    content = result.content.strip()
    if not content:
        # 局部导入避开 ports→generation 的运行时契约环；空输出按 LLM 失败交由管线静默处理。
        from quanta_bot.pipeline.ports import LLMClientError

        raise LLMClientError(
            "生成模型返回空内容",
            prompt_tokens=prompt_tokens,
            completion_tokens=completion_tokens,
            usage_complete=usage_complete,
            validation_failed=True,
        )
    if sum(content.count(mark) for mark in ("?", "？")) > 1:
        from quanta_bot.pipeline.ports import LLMClientError

        try:
            repaired_result = await llm.complete(
                system=f"{system}\n\n{_FORMAT_REPAIR_INSTRUCTION}",
                user=user_prompt,
            )  # 首答问题过多时只做一次通用格式修复，不回灌首答原文
        except LLMClientError as exc:
            retry_prompt_tokens = getattr(exc, "prompt_tokens", None)
            retry_completion_tokens = getattr(exc, "completion_tokens", None)
            retry_usage_complete = bool(
                getattr(exc, "usage_complete", False)
                and retry_prompt_tokens is not None
                and retry_completion_tokens is not None
            )
            retry_usage_partial = (
                retry_prompt_tokens is not None or retry_completion_tokens is not None
            )
            if retry_prompt_tokens is not None:
                prompt_tokens += retry_prompt_tokens
            if retry_completion_tokens is not None:
                completion_tokens += retry_completion_tokens
            retry_usage_status = (
                "usage 已知"
                if retry_usage_complete
                else "usage 部分已知"
                if retry_usage_partial
                else "usage 未知"
            )
            raise LLMClientError(
                f"格式修复第二次调用失败（第二次调用 {retry_usage_status}）：{exc}",
                prompt_tokens=prompt_tokens,
                completion_tokens=completion_tokens,
                usage_complete=bool(usage_complete and retry_usage_complete),
                validation_failed=getattr(exc, "validation_failed", False),
            ) from exc
        prompt_tokens += repaired_result.prompt_tokens
        completion_tokens += repaired_result.completion_tokens
        usage_complete = usage_complete and repaired_result.usage_complete
        content = repaired_result.content.strip()
        if not content:
            from quanta_bot.pipeline.ports import LLMClientError

            raise LLMClientError(
                "格式修复后生成模型返回空内容",
                prompt_tokens=prompt_tokens,
                completion_tokens=completion_tokens,
                usage_complete=usage_complete,
                validation_failed=True,
            )
        if sum(content.count(mark) for mark in ("?", "？")) > 1:
            from quanta_bot.pipeline.ports import LLMClientError

            raise LLMClientError(
                "格式修复后生成回复显式问题超过一个",
                prompt_tokens=prompt_tokens,
                completion_tokens=completion_tokens,
                usage_complete=usage_complete,
                validation_failed=True,
            )
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
        prompt_tokens=prompt_tokens,
        completion_tokens=completion_tokens,
        usage_complete=usage_complete,
    )
