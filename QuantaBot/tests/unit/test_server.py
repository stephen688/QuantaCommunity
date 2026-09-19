"""server 行为测试：fake /health 摘要 + 真模式缺配置的降级态（无网络）。"""

from types import SimpleNamespace

from fastapi.testclient import TestClient

import quanta_bot.server as server
from quanta_bot.infra.content_sync import FakeContentSource
from quanta_bot.infra.kv import InMemoryKV
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

        live = client.get("/live")
        assert live.status_code == 200
        assert live.json()["status"] == "ok"


def test_health_real_mode_unconfigured_shows_not_configured(tmp_path) -> None:
    """真模式全未配置：mq/llm/tracing/vector/main_service=not_configured，kv=降级标记。"""
    settings = _settings(
        fake_mode=False,
        audit_db_path=str(tmp_path / "d.db"),
        qdrant_url="",  # 防探测发网络请求（hermetic）
    )
    with TestClient(create_app(settings)) as client:
        resp = client.get("/health")
        assert resp.status_code == 503
        assert resp.json()["status"] == "not_ready"
        deps = resp.json()["dependencies"]
        assert deps["mq"] == "not_configured"
        assert deps["llm"] == "not_configured"
        assert deps["tracing"] == "not_configured"
        assert deps["vector"] == "not_configured"
        assert deps["kv"] == "degraded (in-memory)"
        assert deps["main_service"] == "not_configured"


async def test_main_service_health_probe_uses_non_erroring_history_endpoint(
    monkeypatch,
) -> None:
    """就绪探测不能拿不存在的 commentId 反复制造业务错误日志。"""
    probes: list[tuple[str, dict[str, object] | None]] = []

    async def fake_probe(url, headers=None, params=None):
        probes.append((url, params))
        return True

    async def fake_mq_probe(url, timeout_seconds):
        return True

    monkeypatch.setattr(server, "_probe", fake_probe)
    monkeypatch.setattr(server.infra_mq, "check_connection", fake_mq_probe)
    settings = _settings(
        fake_mode=False,
        mq_url="amqp://mq",
        redis_url="redis://redis",
        deepseek_api_key="key",
        langfuse_public_key="public",
        langfuse_secret_key="secret",
        qdrant_url="http://qdrant",
        main_service_base_url="http://demo0",
        main_service_token="token",
        main_service_bot_user_id=10000,
    )
    runtime = SimpleNamespace(deps=SimpleNamespace(kv=InMemoryKV()))

    await server._check_dependencies(settings, runtime)

    assert probes[-1] == (
        "http://demo0/bot/comment/history",
        {"userId": 10000, "postId": 0, "pageNum": 1, "pageSize": 1},
    )


def test_lifespan_initializes_rag_content_collection(tmp_path, monkeypatch) -> None:
    """Runtime 启动时同时初始化用户记忆与 RAG 内容 collection。"""

    class _CollectionStub:
        def __init__(self) -> None:
            self.dims: list[int] = []

        async def ensure_collection(self, dim: int) -> None:
            self.dims.append(dim)

    memory = _CollectionStub()
    content = _CollectionStub()
    runtime = SimpleNamespace(
        deps=SimpleNamespace(memory_store=memory),
        rag=SimpleNamespace(index=content),
        start=lambda: None,
        aclose=lambda: _aclose(),
    )

    async def _aclose() -> None:
        return None

    monkeypatch.setattr(server, "build_runtime", lambda settings: runtime)
    settings = _settings(
        fake_mode=False,
        audit_db_path=str(tmp_path / "d.db"),
        qdrant_url="http://qdrant.test",
        embedding_dim=384,
    )

    with TestClient(create_app(settings)):
        pass

    assert memory.dims == [384]
    assert content.dims == [384]


def test_admin_ingest_requires_token_and_returns_upsert_delete_counts(
    tmp_path, monkeypatch
) -> None:
    """管理端点校验可选 token，并分别返回写入与墓碑删除条数。"""

    class _IndexStub:
        def __init__(self) -> None:
            self.docs = []

        async def upsert_docs(self, docs) -> int:
            self.docs.extend(docs)
            return len(docs)

    async def _aclose() -> None:
        return None

    index = _IndexStub()
    runtime = SimpleNamespace(
        deps=SimpleNamespace(memory_store=SimpleNamespace()),
        rag=SimpleNamespace(source=FakeContentSource(), index=index),
        start=lambda: None,
        aclose=lambda: _aclose(),
    )
    monkeypatch.setattr(server, "build_runtime", lambda settings: runtime)
    settings = _settings(admin_token="ingest-secret", audit_db_path=str(tmp_path / "d.db"))

    with TestClient(create_app(settings)) as client:
        assert client.post("/admin/ingest").status_code == 403
        assert (
            client.post("/admin/ingest", headers={"Authorization": "Bearer wrong"}).status_code
            == 403
        )
        response = client.post(
            "/admin/ingest",
            headers={"Authorization": "Bearer ingest-secret"},
        )

    assert response.status_code == 200
    assert response.json() == {"ingested": 8, "deleted": 0}
    assert len(index.docs) == 8
