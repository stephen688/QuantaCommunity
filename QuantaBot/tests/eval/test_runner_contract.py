"""runner 契约单测：YAML 解析 / 断言函数正反例 / 预置记忆版本改写 / Judge 容错（不调真 LLM）。"""

from pathlib import Path

from tests.eval._runner import (
    CaseResult,
    EvalCase,
    _stamp_memories,
    judge_case,
    load_case,
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
            '  comment: { commentId: 2, userId: 42, commentContent: "@QuantaBot 你好" }',
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
    good = "[QuantaBot·AI 学长] 抱抱，先拆个小计划。"
    bad = "[QuantaBot·AI 学长] 建议：\n- 早睡\n- 列清单"
    asserts = [
        {"assert": "reply_no_markdown_list"},
        {"assert": "reply_questions_at_most", "expected": 1},
    ]
    assert verify_case(*_pipeline_case(asserts, good)) == []
    assert verify_case(*_pipeline_case(asserts, bad)) != []


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
        self, system: str, user: str, *, json_mode: bool = False, max_tokens: int | None = None
    ):
        from quanta_bot.pipeline.ports import LLMResult

        return LLMResult(content=self._content, prompt_tokens=10, completion_tokens=5)


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
        judge={"p0": ["红线"], "p1": ["应做到"], "p2": ["加分"]},
    )


async def test_judge_tolerates_empty_and_malformed_json() -> None:
    """Judge 容错：空/截断 JSON 返回失败描述而非抛异常。

    真跑实证（2026-09-17）：推理模型 deepseek-v4-pro 思考计入 max_tokens，
    Judge 上限过低时 content 可为空或半截——抛异常会掩盖 deterministic 断言结果。
    """
    case = _persona_case_with_judge()
    result = CaseResult(decision="replied", reply="一条正常回复")
    empty = await judge_case(case, result, llm=_StaticLLM(""))
    assert empty and "无法解析" in empty[0]
    truncated = await judge_case(
        case, result, llm=_StaticLLM('{"p0": {"pass": true, "reason": "截')
    )
    assert truncated and "无法解析" in truncated[0]


async def test_judge_rejects_non_object_json() -> None:
    """Judge 输出合法 JSON 但非 object（list）：失败描述而非 AttributeError（形状校验与摘要同思路）。"""
    case = _persona_case_with_judge()
    result = CaseResult(decision="replied", reply="一条正常回复")
    non_object = await judge_case(case, result, llm=_StaticLLM('["p0"]'))
    assert non_object and "非 object" in non_object[0]


async def test_judge_passes_on_all_pass_verdict() -> None:
    """Judge 正常路径：三级全 pass → 空失败列表。"""
    case = _persona_case_with_judge()
    result = CaseResult(decision="replied", reply="一条正常回复")
    verdict_json = '{"p0": {"pass": true}, "p1": {"pass": true}, "p2": {"pass": true}}'
    assert await judge_case(case, result, llm=_StaticLLM(verdict_json)) == []
