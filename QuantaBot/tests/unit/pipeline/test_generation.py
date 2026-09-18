"""generation 异步真调行为测试（FakeLLM 注入，hermetic）。"""

import pytest

from quanta_bot.infra.deepseek import FakeLLM
from quanta_bot.pipeline.decision import DecisionResult
from quanta_bot.pipeline.generation import generate
from quanta_bot.pipeline.persona import PersonaLibrary
from quanta_bot.pipeline.ports import LLMClientError, LLMResult
from quanta_bot.pipeline.trigger import TriggerEvent


def _event() -> TriggerEvent:
    return TriggerEvent(
        event_id="evt-11",
        comment_id=11,
        post_id=22,
        commenter_user_id=3,
        content="@QuantaBot hi",
        mentioned_bot=True,
    )


async def test_generate_injects_ai_badge_when_missing() -> None:
    """模型输出无 AI 标识 → 生成层强制补（红线 §0.1：每条回复可被一眼识别为 AI）。"""
    llm = FakeLLM()
    output = await generate(
        _event(), decision=None, llm=llm, context_text="上下文", persona=PersonaLibrary()
    )
    assert output.reply.content.startswith("[QuantaBot·AI 学长]")
    assert "AI" in output.reply.content
    system = llm.calls[0]["system"]
    assert system.startswith("# QuantaBot 人格内核")  # system 以人格内核开头（A 通道内核在前）
    assert "# 模式：生活玩梗" in system  # 无决策 → 默认生活玩梗模式


async def test_generate_keeps_badge_when_present() -> None:
    """模型自带标识则不重复加（自定义 FakeLLM 模拟带标识输出）。"""

    class BadgedFakeLLM(FakeLLM):
        async def complete(self, system: str, user: str) -> LLMResult:
            return LLMResult(
                content="[QuantaBot·AI 学长] 我自己带了标识", prompt_tokens=1, completion_tokens=1
            )

    output = await generate(
        _event(),
        decision=None,
        llm=BadgedFakeLLM(),
        context_text="上下文",
        persona=PersonaLibrary(),
    )
    assert output.reply.content.count("[QuantaBot·AI 学长]") == 1


async def test_generate_rejects_empty_model_content() -> None:
    """模型只返回空白时静默失败，不生成只有 AI 徽章的空壳评论。"""

    class EmptyFakeLLM(FakeLLM):
        async def complete(
            self,
            system: str,
            user: str,
            *,
            json_mode: bool = False,
            max_tokens: int | None = None,
        ) -> LLMResult:
            return LLMResult(content=" \n", prompt_tokens=1, completion_tokens=0)

    with pytest.raises(LLMClientError, match="空内容"):
        await generate(
            _event(),
            decision=None,
            llm=EmptyFakeLLM(),
            context_text="上下文",
            persona=PersonaLibrary(),
        )


async def test_generate_uses_assembled_context_verbatim() -> None:
    """user_prompt=assemble 产物原样：触发评论已由 assemble 触发行承载（红线），M2 后缀曾致三重注入。"""
    llm = FakeLLM()
    context_text = "【主楼】选课帖\n【触发行】@QuantaBot hi"
    await generate(
        _event(), decision=None, llm=llm, context_text=context_text, persona=PersonaLibrary()
    )
    assert llm.calls[0]["user"] == context_text  # 不再追加触发评论后缀（预算口径诚实）


async def test_generate_output_anchors_and_usage() -> None:
    """回复锚点（post/回复对象）与 usage 透传（成本折算输入）。"""
    llm = FakeLLM()
    output = await generate(
        _event(),
        decision=DecisionResult(should_reply=True, mode="生活玩梗", reason="测试"),
        llm=llm,
        context_text="上下文",
        persona=PersonaLibrary(),
    )
    assert output.reply.post_id == 22
    assert output.reply.reply_to_comment_id == 11
    assert output.reply.reply_to_user_id == 3
    assert (
        output.reply.parent_floor_comment_id == 11
    )  # 触发评论为一级评论（parent_id=None）→ 回复挂其下
    assert output.prompt_tokens == 500
    assert output.completion_tokens == 100
    system = llm.calls[0]["system"]
    assert system.startswith("# QuantaBot 人格内核")  # 内核开头（QuantaBot 身份锚定）
    assert "# 模式：生活玩梗" in system  # decision.mode 驱动对应模式文本
