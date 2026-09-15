"""integration 门禁：默认整目录跳过；QUANTABOT_INTEGRATION=1 才收集（CI 不受影响）。

实现说明（对计划的偏差修正）：pytest 9.x 下 conftest.py 顶层的
pytest.skip(allow_module_level=True) 会在 conftest 预加载阶段炸成 collection error
（Skipped 异常无测试项承载，pytest-dev/pytest#12966 行为），无法达成
「默认档全绿、skip 计入」的验收。故改用 module 级 autouse fixture 实现同语义门禁：
未启用时该目录每个用例以 skipped 计入；置 1 时放行真跑。门禁判定与 skip 文案与计划一致。
"""

import os

import pytest

# 本机系统代理免疫（实测：Windows 注册表代理 http://127.0.0.1:7897 会被 httpx 经
# urllib.getproxies() 拾取，回环目标被代理拒收回 502 空响应）。NO_PROXY 是标准
# 豁免机制：仅本套件进程内生效，外部依赖（DeepSeek 等）仍按系统代理正常走，
# 生产代码行为不受影响。
os.environ.setdefault("NO_PROXY", "127.0.0.1,localhost,::1")
os.environ.setdefault("no_proxy", os.environ["NO_PROXY"])

_integration_enabled = os.environ.get("QUANTABOT_INTEGRATION") == "1"


@pytest.fixture(autouse=True, scope="module")
def _integration_gate() -> None:
    if not _integration_enabled:
        pytest.skip(
            "integration 未启用（需 QUANTABOT_INTEGRATION=1 且 compose 全栈/DeepSeek key 就绪）"
        )
