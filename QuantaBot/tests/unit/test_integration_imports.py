"""integration 门禁导入回归：默认 skip 前也必须能收集真依赖模块。"""

import importlib


def test_qdrant_integration_imports_current_settings_module() -> None:
    """旧 quanta_bot.settings 导入不存在时，默认 integration 无法收集。"""
    module = importlib.import_module("tests.integration.test_qdrant_memory_integration")
    assert module.Settings.__module__ == "quanta_bot.infra.settings"
