"""integration 门禁：默认整目录跳过；QUANTABOT_INTEGRATION=1 才收集（CI 不受影响）。

实现说明（对计划的偏差修正）：pytest 9.x 下 conftest.py 顶层的
pytest.skip(allow_module_level=True) 会在 conftest 预加载阶段炸成 collection error
（Skipped 异常无测试项承载，pytest-dev/pytest#12966 行为），无法达成
「默认档全绿、skip 计入」的验收。故改用 module 级 autouse fixture 实现同语义门禁：
未启用时该目录每个用例以 skipped 计入；置 1 时放行真跑。门禁判定与 skip 文案与计划一致。
"""

import os

import pytest

_integration_enabled = os.environ.get("QUANTABOT_INTEGRATION") == "1"


@pytest.fixture(autouse=True, scope="module")
def _integration_gate() -> None:
    if not _integration_enabled:
        pytest.skip(
            "integration 未启用（需 QUANTABOT_INTEGRATION=1 且 compose 全栈/DeepSeek key 就绪）"
        )
