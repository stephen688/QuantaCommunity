"""runner 契约单测：YAML 解析 / 断言函数正反例 / 预置记忆版本改写（不调 LLM）。"""

from pathlib import Path

from tests.eval._runner import CaseResult, EvalCase, _stamp_memories, load_case, verify_case


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
