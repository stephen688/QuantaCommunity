"""server —— FastAPI 入口（lifespan 托管 Runtime；/health 真连通探测）。

职责：应用工厂 create_app()；启动装配 Runtime（真模式起控制面轮询），关闭统一收尾；
      /live 只判进程存活；/health 供 compose 就绪检查与运维探测（关键依赖失败返回 503）。
边界：不写业务链路（pipeline 负责）；不建外部客户端（composition 负责）；
      健康探测统一 5s 探测档（与业务超时档分离——探测不应被 60s LLM 档拖死）。
已知坑：模块级 app = create_app() 在 import 时只建 FastAPI 对象不建连接（Runtime 在 lifespan 才装配）
      ——uvicorn quanta_bot.server:app 与 TestClient 行为一致。
"""

import logging
import secrets
import time
from contextlib import asynccontextmanager

import httpx
from fastapi import FastAPI, Header, HTTPException, Response, status
from starlette.types import ASGIApp, Message, Receive, Scope, Send

from quanta_bot import __version__
from quanta_bot.composition import Runtime, build_runtime
from quanta_bot.crosscutting.trace_context import resolve_trace_id, trace_scope
from quanta_bot.infra import mq as infra_mq
from quanta_bot.infra.content_sync import ingest_content
from quanta_bot.infra.kv import RedisKV
from quanta_bot.infra.logging_config import (
    configure_logging,
    shutdown_logging,
)
from quanta_bot.infra.settings import Settings

logger = logging.getLogger(__name__)

# 健康探测统一档（秒）——与业务超时档分离
_PROBE_TIMEOUT_SECONDS = 5.0


class TraceIdMiddleware:
    """纯 ASGI 请求关联中间件：生成响应头并隔离每个 HTTP 协程上下文。"""

    def __init__(self, app: ASGIApp) -> None:
        self._app = app

    async def __call__(self, scope: Scope, receive: Receive, send: Send) -> None:
        if scope.get("type") != "http":
            await self._app(scope, receive, send)
            return

        candidate_values: list[object] = []
        for key, value in scope.get("headers", []):
            if key.lower() == b"x-request-id":
                try:
                    candidate_values.append(value.decode("ascii"))
                except UnicodeDecodeError:
                    candidate_values.append(None)
        candidate = candidate_values[0] if len(candidate_values) == 1 else None
        trace_id = resolve_trace_id(candidate)
        started_at = time.monotonic()
        status_code = 500

        async def send_with_trace(message: Message) -> None:
            nonlocal status_code
            if message.get("type") == "http.response.start":
                status_code = int(message.get("status", 500))
                response = dict(message)
                response_headers = [
                    (key, value)
                    for key, value in message.get("headers", [])
                    if key.lower() != b"x-request-id"
                ]
                response_headers.append((b"x-request-id", trace_id.encode("ascii")))
                response["headers"] = response_headers
                message = response
            await send(message)

        with trace_scope(trace_id):
            try:
                await self._app(scope, receive, send_with_trace)
            except Exception:
                logger.exception(
                    "HTTP请求未处理 method=%s path=%s",
                    scope.get("method", "-"),
                    scope.get("path", "-"),
                )
                raise
            finally:
                duration_ms = round((time.monotonic() - started_at) * 1000)
                logger.info(
                    "http_request_completed method=%s path=%s status=%s durationMs=%s",
                    scope.get("method", "-"),
                    scope.get("path", "-"),
                    status_code,
                    duration_ms,
                )


class CorrelatedFastAPI(FastAPI):
    """将请求关联放到错误处理中间件外侧，使默认 500 响应也带编号。"""

    def build_middleware_stack(self) -> ASGIApp:
        return TraceIdMiddleware(super().build_middleware_stack())


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

    # 主服务（demo0）：用空历史分页做 C-2 连通探测；避免 commentId=0 的 chain 查询
    # 持续制造“评论不存在”业务错误日志。URL 未配=not_configured。
    if not settings.main_service_base_url:
        main_service_status = "not_configured"
    else:
        ok = await _probe(
            f"{settings.main_service_base_url}/bot/comment/history",
            params={
                "userId": settings.main_service_bot_user_id,
                "postId": 0,
                "pageNum": 1,
                "pageSize": 1,
            },
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
    logging_runtime = configure_logging(settings)
    runtime: Runtime | None = None
    try:
        runtime = build_runtime(settings)
        # Qdrant 记忆 collection 幂等创建（真模式 + QdrantStore 时；失败 WARNING 不阻断启动
        # ——记忆是可降级通道，运行期召回失败走管线降级路径）
        memory_store = runtime.deps.memory_store
        if settings.qdrant_url and hasattr(memory_store, "ensure_collection"):
            try:
                await memory_store.ensure_collection(settings.embedding_dim)
            except Exception as exc:
                logger.warning("Qdrant collection 初始化失败（记忆将走运行期降级）：%s", exc)
        rag = getattr(runtime, "rag", None)
        content_index = getattr(rag, "index", None)
        if (
            settings.qdrant_url
            and content_index is not None
            and hasattr(content_index, "ensure_collection")
        ):
            # 内容索引维度错配会令所有摄取/检索失败，必须在启动时显式阻断。
            await content_index.ensure_collection(settings.embedding_dim)
        runtime.start()
        app.state.runtime = runtime
        yield
    finally:
        try:
            if runtime is not None:
                await runtime.aclose()
        finally:
            shutdown_logging(logging_runtime)


def create_app(settings: Settings | None = None) -> FastAPI:
    """构建 FastAPI 应用；settings 缺省时读环境配置（uvicorn 入口路径）。"""
    s = settings or Settings()
    app = CorrelatedFastAPI(title="QuantaBot", version=__version__, lifespan=_lifespan)
    app.state.settings = s

    @app.get("/live")
    async def live() -> dict:
        """进程存活探针：只证明事件循环仍能响应，不代表依赖已就绪。"""
        return {"status": "ok", "version": __version__}

    @app.get("/health")
    async def health(response: Response) -> dict:
        """就绪探针：关键依赖不可用时返回 503，并展示可降级的 tracing 状态。"""
        runtime: Runtime | None = getattr(app.state, "runtime", None)
        dependencies = await _check_dependencies(s, runtime)
        switches = runtime.deps.control_plane.snapshot.model_dump() if runtime else {}
        required_dependencies = {"mq", "kv", "main_service", "llm", "vector"}
        ready_states = {"fake", "ok"}
        is_ready = all(dependencies[name] in ready_states for name in required_dependencies)
        if not is_ready:
            response.status_code = status.HTTP_503_SERVICE_UNAVAILABLE
        return {
            "status": "ok" if is_ready else "not_ready",
            "version": __version__,
            "app_env": s.app_env,
            "fake_mode": s.fake_mode,
            "dependencies": dependencies,
            "switches": switches,
        }

    @app.post("/admin/ingest")
    async def ingest(authorization: str = Header(default="")) -> dict:
        """手动触发 RAG 摄取（运营/联调用；定时自动化归 M5）。

        admin_token 配置时校验 Bearer 凭据（不符 403）；RAG 摄取未配置 503（qdrant/embedding/main_service
        缺一；检索可能仍可使用已有索引）。
        """
        runtime: Runtime | None = getattr(app.state, "runtime", None)
        bearer_prefix = "Bearer "
        supplied_token = (
            authorization[len(bearer_prefix) :] if authorization.startswith(bearer_prefix) else ""
        )
        if s.admin_token and not secrets.compare_digest(supplied_token, s.admin_token):
            raise HTTPException(status_code=403, detail="admin_token 不符")
        if runtime is None or runtime.rag is None:
            raise HTTPException(
                status_code=503,
                detail="RAG 摄取未配置（需 qdrant/embedding/main_service）",
            )
        upserted, deleted = await ingest_content(runtime.rag.source, runtime.rag.index)
        return {"ingested": upserted, "deleted": deleted}

    return app


# uvicorn 入口：uv run uvicorn quanta_bot.server:app（默认端口 8000）
app = create_app()
