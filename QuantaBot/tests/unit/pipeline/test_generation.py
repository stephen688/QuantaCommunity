"""generation 异步真调行为测试（FakeLLM 注入，hermetic）。"""

from quanta_bot.infra.deepseek import FakeLLM
from quanta_bot.pipeline.decision import DecisionResult
from quanta_bot.pipeline.generation import generate
from quanta_bot.pipeline.ports import LLMResult
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
    output = await generate(_event(), decision=None, llm=FakeLLM(), context_text="上下文")
    assert output.reply.content.startswith("[QuantaBot·AI 学长]")
    assert "AI" in output.reply.content


async def test_generate_keeps_badge_when_present() -> None:
    """模型自带标识则不重复加（自定义 FakeLLM 模拟带标识输出）。"""

    class BadgedFakeLLM(FakeLLM):
        async def complete(self, system: str, user: str) -> LLMResult:
            return LLMResult(
                content="[QuantaBot·AI 学长] 我自己带了标识", prompt_tokens=1, completion_tokens=1
            )

    output = await generate(_event(), decision=None, llm=BadgedFakeLLM(), context_text="上下文")
    assert output.reply.content.count("[QuantaBot·AI 学长]") == 1


async def test_generate_output_anchors_and_usage() -> None:
    """回复锚点（post/回复对象）与 usage 透传（成本折算输入）。"""
    output = await generate(
        _event(),
        decision=DecisionResult(should_reply=True, mode="生活玩梗", reason="测试"),
        llm=FakeLLM(),
        context_text="上下文",
    )
    assert output.reply.post_id == 22
    assert output.reply.reply_to_comment_id == 11
    assert output.prompt_tokens == 500
    assert output.completion_tokens == 100
