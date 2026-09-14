"""server 入口的行为测试：/health 返回状态/版本/配置摘要与依赖占位。"""

from fastapi.testclient import TestClient

from quanta_bot import __version__
from quanta_bot.infra.settings import Settings
from quanta_bot.server import create_app


def test_health_returns_ok_and_summary() -> None:
    """/health 返回 200 + 版本 + 配置摘要 + 依赖连通占位（M2 起接真实连通性）。"""
    settings = Settings(app_env="dev", fake_mode=True)
    client = TestClient(create_app(settings))
    resp = client.get("/health")
    assert resp.status_code == 200
    body = resp.json()
    assert body["status"] == "ok"
    assert body["version"] == __version__
    assert body["app_env"] == "dev"
    assert body["fake_mode"] is True
    assert isinstance(body["dependencies"], dict)
