"""server 行为测试：fake /health 摘要 + 真模式缺配置的降级态（无网络）。"""

from fastapi.testclient import TestClient

from quanta_bot.infra.settings import Settings
from quanta_bot.server import create_app


def _settings(**overrides) -> Settings:
    base = dict(_env_file=None, fake_mode=True)
    base.update(overrides)
    return Settings(**base)


def test_health_fake_mode(tmp_path) -> None:
    """fake /health：依赖全 fake + 开关快照字段（audit 落 tmp 防污染仓库 data/）。"""
    with TestClient(create_app(_settings(audit_db_path=str(tmp_path / "d.db")))) as client:
        resp = client.get("/health")
        assert resp.status_code == 200
        body = resp.json()
        assert body["status"] == "ok"
        assert body["fake_mode"] is True
        deps = body["dependencies"]
        assert set(deps.keys()) == {"mq", "kv", "main_service", "llm", "vector", "tracing"}
        assert all(v == "fake" for v in deps.values())
        assert body["switches"]["kill"] is False


def test_health_real_mode_unconfigured_shows_not_configured(tmp_path) -> None:
    """真模式全未配置：mq/llm/tracing/vector/main_service=not_configured，kv=降级标记。"""
    settings = _settings(
        fake_mode=False,
        audit_db_path=str(tmp_path / "d.db"),
        qdrant_url="",  # 防探测发网络请求（hermetic）
    )
    with TestClient(create_app(settings)) as client:
        resp = client.get("/health")
        assert resp.status_code == 200
        deps = resp.json()["dependencies"]
        assert deps["mq"] == "not_configured"
        assert deps["llm"] == "not_configured"
        assert deps["tracing"] == "not_configured"
        assert deps["vector"] == "not_configured"
        assert deps["kv"] == "degraded (in-memory)"
        assert deps["main_service"] == "not_configured"
