"""eval 套件：遍历 eval/cases/*.yaml——pipeline 档全环境跑（FakeLLM 剧本，CI 可跑）；
persona 档由 conftest 按 QUANTABOT_EVAL 放行（真调 DeepSeek 生成 + Judge）。"""

from pathlib import Path

import pytest
from tests.eval._runner import CASES_DIR, judge_case, load_case, run_case, verify_case

CASES = sorted(CASES_DIR.glob("*.yaml"))


@pytest.mark.parametrize("case_path", [pytest.param(path, id=path.stem) for path in CASES])
async def test_eval_case(case_path: Path) -> None:
    """单条用例：跑 fake 管线（persona 档真 LLM）→ deterministic 断言 + Judge（persona 档）。"""
    case = load_case(case_path)
    result = await run_case(case)
    failures = list(verify_case(case, result))
    if case.tier == "persona":  # 能执行到这说明 QUANTABOT_EVAL=1（否则已被 conftest skip）
        failures += await judge_case(case, result)
    assert not failures, f"[{case.id}] 断言失败：{failures}"
