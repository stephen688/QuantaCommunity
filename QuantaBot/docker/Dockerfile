# QuantaBot 应用镜像：多阶段构建（依赖层缓存 + 非 root 运行；技术选型 §10.3）
# uv 镜像锁与本地一致（uv --version = 0.11.28）
FROM python:3.12-slim AS builder
COPY --from=ghcr.io/astral-sh/uv:0.11.28 /uv /uvx /bin/
WORKDIR /app
COPY pyproject.toml uv.lock ./
# --no-install-project：依赖层只装三方（源码改动不破缓存；运行层用 PYTHONPATH 载入源码）
RUN uv sync --frozen --no-dev --no-install-project
COPY src ./src

FROM python:3.12-slim
RUN useradd --create-home --uid 1000 quanta
WORKDIR /app
COPY --from=builder /app/.venv /app/.venv
COPY --from=builder /app/src /app/src
ENV PATH="/app/.venv/bin:$PATH" PYTHONPATH="/app/src" PYTHONUNBUFFERED=1
USER quanta
EXPOSE 8000
CMD ["uvicorn", "quanta_bot.server:app", "--host", "0.0.0.0", "--port", "8000"]
