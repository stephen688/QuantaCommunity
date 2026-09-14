"""server —— FastAPI 入口（/health 与管理端点）。

职责：应用工厂 create_app()；/health 供 compose 健康检查与运维探测（M2 起接入真实依赖连通性）。
边界：不写业务链路（pipeline 负责）；不建外部客户端（composition.py 负责，M1+ 引入）。
"""

from fastapi import FastAPI

from quanta_bot import __version__
from quanta_bot.infra.settings import Settings


def create_app(settings: Settings | None = None) -> FastAPI:
    """构建 FastAPI 应用；settings 缺省时读环境配置（uvicorn 入口路径）。"""
    s = settings or Settings()
    app = FastAPI(title="QuantaBot", version=__version__)

    @app.get("/health")
    async def health() -> dict:
        """健康检查：状态 + 版本 + 配置摘要 + 依赖连通占位。"""
        return {
            "status": "ok",
            "version": __version__,
            "app_env": s.app_env,
            "fake_mode": s.fake_mode,
            "dependencies": {
                "mq": "not_configured",
                "main_service": "not_configured",
                "kv": "not_configured",
                "vector": "not_configured",
                "tracing": "not_configured",
            },
        }

    return app


# uvicorn 入口：uv run uvicorn quanta_bot.server:app（默认端口 8000）
app = create_app()
