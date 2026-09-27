"""runner 契约单测：YAML 解析 / 断言函数正反例 / 预置记忆版本改写 / Judge 容错（不调真 LLM）。"""

from pathlib import Path

import pytest
from pydantic import ValidationError
from tests.eval import _runner as runner
from tests.eval._runner import (
    _ASSERTIONS,
    CASES_DIR,
    CaseResult,
    EvalCase,
    _stamp_memories,
    judge_case,
    load_case,
    run_case,
    verify_case,
)


def test_load_case_parses_yaml_contract(tmp_path: Path) -> None:
    """YAML → EvalCase：字段解析与默认值（judge/memories 缺省 None/[]）。"""
    yaml_text = "\n".join(
        [
            "id: demo-1",
            "scenario: 1",
            "scenario_name: 演示",
            "mode: 专业答疑",
            "tier: pipeline",
            "trigger:",
            "  post: { postId: 1, userId: 1, title: t, content: 主楼 }",
            '  comment: { commentId: 2, userId: 42, commentContent: "@框框 你好" }',
            "deterministic:",
            "  - { assert: decision_is, expected: replied }",
        ]
    )
    path = tmp_path / "demo-1.yaml"
    path.write_text(yaml_text, encoding="utf-8")
    case = load_case(path)
    assert case.tier == "pipeline" and case.judge is None and case.memories == []
    assert case.deterministic == [{"assert": "decision_is", "expected": "replied"}]


def _pipeline_case(asserts: list[dict], reply: str) -> tuple[EvalCase, CaseResult]:
    """契约测试辅助：最小用例与结果对。"""
    case = EvalCase(
        id="d",
        scenario=0,
        scenario_name="",
        mode="专业答疑",
        tier="pipeline",
        trigger={},
        deterministic=asserts,
    )
    return case, CaseResult(decision="replied", reply=reply)


def test_deterministic_assertions_positive_and_negative() -> None:
    """断言函数正反例：反格式化红线与问句上限（可执行判据，非 Judge 感觉）。"""
    good = "[框框·AI 学长] 抱抱，先拆个小计划。"
    bad = "[框框·AI 学长] 建议：\n- 早睡\n- 列清单"
    asserts = [
        {"assert": "reply_no_markdown_list"},
        {"assert": "reply_questions_at_most", "expected": 1},
    ]
    assert verify_case(*_pipeline_case(asserts, good)) == []
    assert verify_case(*_pipeline_case(asserts, bad)) != []


def test_reply_no_markdown_floor_reference_not_heading() -> None:
    """楼层引用 #9506 不是 markdown 标题；# 后带空格才是标题（Release gate persona-08 误报钉桩）。"""
    asserts = [{"assert": "reply_no_markdown_list"}]
    floor_ref = "[框框·AI 学长] #9506说谁先起谁是英雄，我押睡前把包收好的那位。"
    heading1 = "[框框·AI 学长] 先说结论。\n# 早八攻略\n正文走起"
    heading2 = "[框框·AI 学长] 先说结论。\n## 今晚安排\n正文走起"
    assert verify_case(*_pipeline_case(asserts, floor_ref)) == []
    assert verify_case(*_pipeline_case(asserts, heading1)) != []
    assert verify_case(*_pipeline_case(asserts, heading2)) != []


def test_stamp_memories_overrides_persona_version() -> None:
    """预置记忆版本改写约定：YAML 不写死 hash，runner 以运行时 persona_version 盖章。"""
    stamped = _stamp_memories(
        [
            {
                "memory_id": "m1",
                "user_id": 42,
                "type": "user",
                "content": "考研",
                "created_at": "2026-06-01",
            }
        ],
        "runtime-hash",
    )
    assert stamped[0].persona_version == "runtime-hash"


class _StaticLLM:
    """静态内容 fake LLM（Judge 容错契约测试——注入 runner 不自建真客户端）。"""

    def __init__(self, content: str) -> None:
        self._content = content

    async def complete(
        self,
        system: str,
        user: str,
        *,
        json_mode: bool = False,
        max_tokens: int | None = None,
        temperature: float | None = None,
    ):
        from quanta_bot.pipeline.ports import LLMResult

        return LLMResult(content=self._content, prompt_tokens=10, completion_tokens=5)


class _CapturingLLM(_StaticLLM):
    """捕获 Judge user prompt 的 fake LLM（只观测传入证据，不替代业务行为）。"""

    def __init__(self, content: str) -> None:
        super().__init__(content)
        self.user_prompts: list[str] = []

    async def complete(
        self,
        system: str,
        user: str,
        *,
        json_mode: bool = False,
        max_tokens: int | None = None,
        temperature: float | None = None,
    ):
        self.user_prompts.append(user)
        return await super().complete(
            system,
            user,
            json_mode=json_mode,
            max_tokens=max_tokens,
            temperature=temperature,
        )


def _persona_case_with_judge() -> EvalCase:
    """带 judge 要点的 persona 用例（Judge 容错测试输入；trigger 给最小 post/comment 供 prompt 拼接）。"""
    return EvalCase(
        id="judge-tolerance",
        scenario=0,
        scenario_name="",
        mode="专业答疑",
        tier="persona",
        trigger={"post": {"content": "主楼"}, "comment": {"content": "评论"}},
        deterministic=[],
        judge={"p1": ["应做到"], "p2": ["加分"]},
    )


async def test_judge_prompt_includes_case_evidence() -> None:
    """Judge prompt must expose floors, preset memories, and retrieval references."""
    floor_content = "楼层证据：课程作业截止周五"
    memory_content = "记忆证据：用户正在准备考研"
    retrieval_content = "检索证据：学校图书馆周末开放"
    llm = _CapturingLLM(
        '{"p1": {"score": 4, "reason": "行为符合"}, "p2": {"score": 4, "reason": "表达自然"}}'
    )
    case = EvalCase(
        id="judge-evidence",
        scenario=0,
        scenario_name="",
        mode="专业答疑",
        tier="persona",
        trigger={
            "post": {"content": "主楼"},
            "comment": {"content": "评论"},
            "floors": [{"content": floor_content}],
        },
        memories=[{"content": memory_content}],
        retrieval_fragments=[{"content": retrieval_content}],
        deterministic=[],
        judge={"p1": ["应做到"], "p2": ["加分"]},
    )
    result = CaseResult(decision="replied", reply="一条正常回复")

    judge_result = await judge_case(case, result, llm=llm)
    assert judge_result.failures == []
    assert len(llm.user_prompts) == 1
    prompt = llm.user_prompts[0]
    assert floor_content in prompt
    assert memory_content in prompt
    assert retrieval_content in prompt


async def test_judge_tolerates_empty_and_malformed_json() -> None:
    """Judge 容错：空/截断 JSON 返回失败描述而非抛异常。

    真跑实证（2026-09-17）：推理模型 deepseek-v4-pro 思考计入 max_tokens，
    Judge 上限过低时 content 可为空或半截——抛异常会掩盖 deterministic 断言结果。
    """
    case = _persona_case_with_judge()
    result = CaseResult(decision="replied", reply="一条正常回复")
    empty = await judge_case(case, result, llm=_StaticLLM(""))
    assert empty and "无法解析" in empty[0]
    truncated = await judge_case(case, result, llm=_StaticLLM('{"p1": {"score": 4, "reason": "截'))
    assert truncated and "无法解析" in truncated[0]


async def test_judge_rejects_non_object_json() -> None:
    """Judge 输出合法 JSON 但非 object（list）：失败描述而非 AttributeError（形状校验与摘要同思路）。"""
    case = _persona_case_with_judge()
    result = CaseResult(decision="replied", reply="一条正常回复")
    non_object = await judge_case(case, result, llm=_StaticLLM('["p1"]'))
    assert non_object and "无法解析" in non_object[0]


async def test_judge_passes_on_all_pass_verdict() -> None:
    """Judge 正常路径：P1/P2 均达标 → 空失败列表。"""
    case = _persona_case_with_judge()
    result = CaseResult(decision="replied", reply="一条正常回复")
    verdict_json = (
        '{"p1": {"score": 4, "reason": "行为符合"}, "p2": {"score": 4, "reason": "表达自然"}}'
    )
    assert (await judge_case(case, result, llm=_StaticLLM(verdict_json))).failures == []


def test_judge_rejects_score_outside_one_to_five() -> None:
    """数值 Judge 的单项分数必须冻结在 1～5。"""
    judge_verdict = getattr(runner, "JudgeVerdict", None)
    assert judge_verdict is not None, "数值 Judge schema 尚未注册"
    with pytest.raises(ValidationError):
        judge_verdict.model_validate(
            {"p1": {"score": 6, "reason": "x"}, "p2": {"score": 4, "reason": "y"}}
        )


class _NumericJudgeLLM:
    """返回数值 Judge JSON，并记录评测温度。"""

    def __init__(self, content: str) -> None:
        self._content = content
        self.temperatures: list[float | None] = []

    async def complete(
        self,
        system: str,
        user: str,
        *,
        json_mode: bool = False,
        max_tokens: int | None = None,
        temperature: float | None = None,
    ):
        from quanta_bot.pipeline.ports import LLMResult

        self.temperatures.append(temperature)
        return LLMResult(content=self._content, prompt_tokens=10, completion_tokens=5)


def _numeric_judge_case(case_id: str = "persona-01-course") -> EvalCase:
    """构造只依赖 P1/P2 数值评分的最小 Persona case。"""
    return EvalCase(
        id=case_id,
        scenario=0,
        scenario_name="",
        mode="专业答疑",
        tier="persona",
        trigger={"post": {"content": "主楼"}, "comment": {"content": "评论"}},
        deterministic=[],
        judge={"p1": ["行为"], "p2": ["表达"]},
    )


@pytest.mark.parametrize(
    ("case_id", "p1", "p2", "expected_failures"),
    [
        ("persona-01-course", 3, 3, 0),
        ("persona-01-course", 2, 5, 1),
        ("persona-11-comfort", 4, 4, 0),
        ("persona-11-comfort", 3, 5, 1),
    ],
)
async def test_numeric_judge_applies_normal_and_emotion_thresholds(
    case_id: str, p1: int, p2: int, expected_failures: int
) -> None:
    """普通场景最低 3 分，情绪场景最低 4 分，且调用温度固定为 0。"""
    llm = _NumericJudgeLLM(
        f'{{"p1": {{"score": {p1}, "reason": "行为"}}, "p2": {{"score": {p2}, "reason": "表达"}}}}'
    )

    judge_result = await judge_case(
        _numeric_judge_case(case_id),
        CaseResult(decision="replied", reply="一条正常回复"),
        llm=llm,
    )

    assert len(judge_result.failures) == expected_failures
    assert judge_result.verdict is not None
    assert judge_result.prompt_tokens == 10
    assert judge_result.completion_tokens == 5
    assert llm.temperatures == [0.0]


async def test_numeric_judge_parse_failure_is_infra_blocked() -> None:
    """Judge 无法解析时必须归为基础设施阻断，而不是能力断言失败。"""
    judge_result = await judge_case(
        _numeric_judge_case(),
        CaseResult(decision="replied", reply="一条正常回复"),
        llm=_NumericJudgeLLM("截断"),
    )

    assert judge_result.failure_kind == "INFRA_BLOCKED"


# ---- M5 runner 扩展（preset_cost_li / light_llm_content / kv_presets）----


async def test_preset_cost_li_and_light_content_flow() -> None:
    """预置成本键 + 轻模型内容端到端：吃紧档 replied、trace 留痕、断言函数可判。"""
    case = load_case(CASES_DIR / "pipeline-cost-tight-light.yaml")
    result = await run_case(case)
    assert result.decision == "replied"
    assert result.trace is not None and result.trace.cost_tier == "tight"
    assert result.trace.light_model_used is True
    assert _ASSERTIONS["cost_tier_is"]("tight", result) is True
    assert _ASSERTIONS["light_model_used_is"](True, result) is True


async def test_runner_applies_kv_presets_before_pipeline() -> None:
    """runner 必须把通用键预置刷新进控制面，否则 kill 用例会误跑完整管线。"""
    case = EvalCase(
        id="runner-kv-preset",
        scenario=0,
        scenario_name="runner kv preset",
        mode="生活玩梗",
        tier="pipeline",
        kv_presets=[{"key": "quantabot:switch:kill", "value": "true", "ttl_seconds": 3600}],
        trigger={
            "post": {"postId": 3010, "userId": 1, "title": "闲聊", "content": "今天天气不错"},
            "comment": {"commentId": 9110, "userId": 42, "content": "@框框 今天天气不错啊"},
        },
    )

    result = await run_case(case)

    assert result.decision == "skipped_killswitch"


async def test_runner_opens_requested_breaker_before_pipeline() -> None:
    """runner 的熔断预置必须在决策 LLM 调用前生效。"""
    case = EvalCase(
        id="runner-llm-breaker",
        scenario=0,
        scenario_name="runner breaker preset",
        mode="专业答疑",
        tier="pipeline",
        breakers_open=["llm"],
        trigger={
            "post": {"postId": 3011, "userId": 1, "title": "求助", "content": "图书馆几点闭馆"},
            "comment": {"commentId": 9111, "userId": 42, "content": "@框框 图书馆几点闭馆呀"},
        },
    )

    result = await run_case(case)

    assert result.decision == "failed_breaker"
