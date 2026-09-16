"""server —— FastAPI 入口（lifespan 托管 Runtime；/health 真连通探测）。

职责：应用工厂 create_app()；启动装配 Runtime（真模式起控制面轮询），关闭统一收尾；
      /health 供 compose 健康检查与运维探测（fake=fake 标记；真=逐项探测，缺配置=not_configured）。
边界：不写业务链路（pipeline 负责）；不建外部客户端（composition 负责）；
      健康探测统一 5s 探测档（与业务超时档分离——探测不应被 60s LLM 档拖死）。
已知坑：模块级 app = create_app() 在 import 时只建 FastAPI 对象不建连接（Runtime 在 lifespan 才装配）
      ——uvicorn quanta_bot.server:app 与 TestClient 行为一致。
"""

import logging
from contextlib import asynccontextmanager

import httpx
from fastapi import FastAPI

from quanta_bot import __version__
from quanta_bot.composition import Runtime, build_runtime
from quanta_bot.infra import mq as infra_mq
from quanta_bot.infra.kv import RedisKV
from quanta_bot.infra.settings import Settings

logger = logging.getLogger(__name__)

# 健康探测统一档（秒）——与业务超时档分离
_PROBE_TIMEOUT_SECONDS = 5.0


async def _probe(
    url: str, headers: dict[str, str] | None = None, params: dict[str, object] | None = None
) -> bool:
    """HTTP GET 探测（200 即通）。"""
    try:
        async with httpx.AsyncClient(
            timeout=_PROBE_TIMEOUT_SECONDS, headers=headers or {}
        ) as client:
            resp = await client.get(url, params=params)
            return resp.status_code == 200
    except httpx.HTTPError:
        return False


async def _check_dependencies(settings: Settings, runtime: Runtime | None) -> dict[str, str]:
    """逐依赖连通性（fake=标记；真=探测；缺配置=not_configured）。"""
    if settings.fake_mode:
        return {
            "mq": "fake",
            "kv": "fake",
            "main_service": "fake",
            "llm": "fake",
            "vector": "fake",
            "tracing": "fake",
        }

    deps = runtime.deps if runtime is not None else None

    # MQ（P0-1 未接线也可探测可达性——compose dev 栈验收需要）
    # [Task 17] mq 项含义升级为「连通 + 消费者运行」：consumer（runtime.consumer）由 lifespan
    # 托管、run_forever 自带断线重连；本探测逻辑保持连通性检查不变（消费运行态随
    # run_forever 常驻，异常自动 5s 重连恢复，无需在此叠加探测）。
    if not settings.mq_url:
        mq_status = "not_configured"
    else:
        mq_status = (
            "ok"
            if await infra_mq.check_connection(settings.mq_url, _PROBE_TIMEOUT_SECONDS)
            else "error"
        )

    # KV：Redis ping；内存降级明示；runtime 未就绪（lifespan 未完成）标 starting
    if deps is None:
        kv_status = "starting"
    elif isinstance(deps.kv, RedisKV):
        kv_status = "ok" if await deps.kv.ping() else "error"
    else:
        kv_status = "degraded (in-memory)"

    # LLM：DeepSeek /models 探测（key 未配=not_configured）
    if not settings.deepseek_api_key:
        llm_status = "not_configured"
    else:
        ok = await _probe(
            f"{settings.deepseek_base_url}/models",
            headers={"Authorization": f"Bearer {settings.deepseek_api_key}"},
        )
        llm_status = "ok" if ok else "error"

    # Tracing：Langfuse 健康端点（密钥未配=not_configured）
    if not (settings.langfuse_public_key and settings.langfuse_secret_key):
        tracing_status = "not_configured"
    else:
        ok = await _probe(f"{settings.langfuse_host}/api/public/health")
        tracing_status = "ok" if ok else "error"

    # Vector：Qdrant readyz（URL 留空=not_configured）
    if not settings.qdrant_url:
        vector_status = "not_configured"
    else:
        vector_status = "ok" if await _probe(f"{settings.qdrant_url}/readyz") else "error"

    # 主服务（demo0）：C-2 chain 端点连通探测（URL 未配=not_configured；demo0 未上线时
    # 如实报 error——compose healthcheck 只看 /health HTTP 200，A2 验收清单不含本项）
    if not settings.main_service_base_url:
        main_service_status = "not_configured"
    else:
        ok = await _probe(
            f"{settings.main_service_base_url}/bot/comment/chain",
            params={"commentId": 0},
            headers=(
                {"Authorization": f"Bearer {settings.main_service_token}"}
                if settings.main_service_token
                else None
            ),
        )
        main_service_status = "ok" if ok else "error"

    return {
        "mq": mq_status,
        "kv": kv_status,
        "main_service": main_service_status,
        "llm": llm_status,
        "vector": vector_status,
        "tracing": tracing_status,
    }


@asynccontextmanager
async def _lifespan(app: FastAPI):
    """启动装配 Runtime；关闭统一收尾（真模式才起控制面轮询）。"""
    settings: Settings = app.state.settings
    runtime = build_runtime(settings)
    runtime.start()
    app.state.runtime = runtime
    yield
    await runtime.aclose()


def create_app(settings: Settings | None = None) -> FastAPI:
    """构建 FastAPI 应用；settings 缺省时读环境配置（uvicorn 入口路径）。"""
    s = settings or Settings()
    app = FastAPI(title="QuantaBot", version=__version__, lifespan=_lifespan)
    app.state.settings = s

    @app.get("/health")
    async def health() -> dict:
        """健康检查：状态 + 版本 + 配置摘要 + 逐依赖连通 + 控制面开关快照。"""
        runtime: Runtime | None = getattr(app.state, "runtime", None)
        dependencies = await _check_dependencies(s, runtime)
        switches = runtime.deps.control_plane.snapshot.model_dump() if runtime else {}
        return {
            "status": "ok",
            "version": __version__,
            "app_env": s.app_env,
            "fake_mode": s.fake_mode,
            "dependencies": dependencies,
            "switches": switches,
        }

    return app


# uvicorn 入口：uv run uvicorn quanta_bot.server:app（默认端口 8000）
app = create_app()
