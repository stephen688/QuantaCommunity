"""决策层 v2 测试：硬规则零成本拦截 + 一车四用 JSON 契约 + 喂回自愈。"""

from datetime import datetime

from quanta_bot.infra.deepseek import FakeLLM
from quanta_bot.memory.ports import MemoryRecord
from quanta_bot.pipeline.decision import DecisionResult, decide, hard_low_value, parse_decision_json
from quanta_bot.pipeline.trigger import TriggerEvent


def _event(content: str = "@QuantaBot 选课求建议") -> TriggerEvent:
    return TriggerEvent(
        eventId="e1",
        commentId=1,
        postId=10,
        commenterUserId=42,
        commentContent=content,
        mentionedBot=True,
    )


def test_hard_low_value_catches_trivial_triggers() -> None:
    """硬规则层：超短/纯符号/重复刷屏/纯链接零成本拦截（§5.6 ①）。"""
    assert hard_low_value("@QuantaBot") is not None  # 仅 @
    assert hard_low_value("@QuantaBot 哈") is not None  # 超短
    assert hard_low_value("@QuantaBot ？？？!!!") is not None  # 纯符号
    assert hard_low_value("@QuantaBot 哈哈哈哈哈") is not None  # 重复刷屏
    assert hard_low_value("@QuantaBot https://x.com/a") is not None  # 纯链接
    assert hard_low_value("@QuantaBot 求选课建议") is None  # 正常通过


def test_hard_low_value_blacklist_words() -> None:
    """黑词表：代写论文命中拦截；内推不在表内（场景 17 求职内推必须能接）。"""
    assert hard_low_value("@QuantaBot 有代写论文的渠道吗") is not None
    assert hard_low_value("@QuantaBot 学长有内推机会吗") is None


def test_parse_decision_json_full_contract() -> None:
    """一车四用输出契约：低价值/模式/置信度/检索flag/精选/四态。"""
    raw = (
        '{"should_reply": true, "mode": "专业答疑", "confidence": 0.9, "reason": "真诚求助",'
        ' "need_retrieval": true, "memory_selection": ["m1"],'
        ' "memory_ops": [{"op": "ADD", "type": "project", "content": "在准备 2026 考研", "why": "动态"}]}'
    )
    result = parse_decision_json(raw)
    assert isinstance(result, DecisionResult)
    assert result.should_reply and result.mode == "专业答疑" and result.need_retrieval
    assert result.memory_selection == ("m1",)
    assert result.memory_ops[0].op == "ADD" and result.memory_ops[0].type == "project"


async def test_decide_feeds_back_on_malformed_json_once() -> None:
    """JSON 畸形喂回自愈：第一次坏 JSON → 纠偏提示重试 → 第二次好 JSON 通过（蓝图 §5.5）。"""
    bad = "这不是JSON"
    good = '{"should_reply": true, "mode": "生活玩梗", "confidence": 0.8, "reason": "玩梗求互动"}'
    llm = FakeLLM(responses=[bad, good])
    result = await decide(_event(), llm, post_digest="主楼内容", nearby_digest="", candidates=())
    assert result.mode == "生活玩梗" and len(llm.calls) == 2  # 恰好重试一次
    assert "缺失" in str(llm.calls[1]["user"]) or "纠偏" in str(
        llm.calls[1]["user"]
    )  # 重试带纠偏上下文


async def test_decide_raises_after_second_malformed() -> None:
    """两次畸形 → LLMClientError（管线 failed 分支静默不回——用户可见链路不喂回，蓝图 §5.5 边界）。"""
    from quanta_bot.pipeline.ports import LLMClientError

    llm = FakeLLM(responses=["坏", "还是坏"])
    try:
        await decide(_event(), llm, post_digest="", nearby_digest="", candidates=())
        raise AssertionError("应当抛 LLMClientError")
    except LLMClientError:
        pass


async def test_decide_prompt_contains_candidates_and_gates() -> None:
    """提示词契约：禁存三重门关键词进 system、候选记忆 content 进 user（LLM 输入材料可对质）。"""
    good = '{"should_reply": true, "mode": "专业答疑", "confidence": 0.85, "reason": "真诚求助"}'
    llm = FakeLLM(responses=[good])
    candidate = MemoryRecord(
        memory_id="m1",
        user_id=42,
        type="project",
        content="在准备 2026 考研",
        created_at=datetime(2026, 9, 1),
        persona_version="persona-test",
    )
    result = await decide(
        _event(), llm, post_digest="主楼摘要", nearby_digest="近区楼层", candidates=(candidate,)
    )
    assert result.should_reply
    system_prompt = str(llm.calls[0]["system"])
    user_prompt = str(llm.calls[0]["user"])
    # 三重门（禁存纪律）进了 system 提示词
    assert "跨会话" in system_prompt
    assert "现场推不出来" in system_prompt
    assert "能改变未来回复" in system_prompt
    # 候选记忆进了 user 提示词
    assert "在准备 2026 考研" in user_prompt
