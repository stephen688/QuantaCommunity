"""eval 门禁：persona 档（persona- 前缀用例）默认 skip；QUANTABOT_EVAL=1 放行真调 DeepSeek。"""

import pytest
from tests.eval._runner import EVAL_PERSONA_ENABLED


def pytest_collection_modifyitems(config, items):
    """persona 档用例（persona- 前缀）：统一打 persona marker（pyproject 注册标记的消费方，
    `-m persona` 过滤可用）；无 QUANTABOT_EVAL=1 时再叠 skip（真调 LLM 产生费用）。"""
    skip = (
        None
        if EVAL_PERSONA_ENABLED
        else pytest.mark.skip(reason="persona 档需 QUANTABOT_EVAL=1（真调 DeepSeek，产生费用）")
    )
    for item in items:
        if "persona-" in item.nodeid:
            item.add_marker(pytest.mark.persona)
            if skip is not None:
                item.add_marker(skip)
