"""infra/settings —— 配置收口（全仓库唯一读取环境变量/.env 的模块）。

职责：定义 Settings 模型与默认值；业务模块只从这里拿配置，禁止直接触碰 os.environ。
边界：不建任何外部连接；不做依赖连通性检测（/health 负责呈现）；字段与 .env.example 一一对应。
已知坑：env_file 必须锚定 PROJECT_ROOT 绝对路径（相对路径按 cwd 解析，uvicorn 从不同目录启动会读不到 .env——M0 遗留 Minor-2，本版清账）。
"""

from pathlib import Path
from typing import Literal

from pydantic_settings import BaseSettings, SettingsConfigDict

# 项目根（QuantaBot/）：settings.py 位于 src/quanta_bot/infra/，向上三级
PROJECT_ROOT = Path(__file__).resolve().parents[3]


def resolve_data_path(path: str) -> Path:
    """把相对路径锚到项目根（防 cwd 漂移导致 data/ 落错地方）；绝对路径原样返回。"""
    p = Path(path)
    return p if p.is_absolute() else PROJECT_ROOT / p


class Settings(BaseSettings):
    """QuantaBot 运行配置（环境变量前缀 QUANTABOT_，支持 .env 文件）。

    纪律：密钥字段（deepseek_api_key / langfuse_*_key）只进 .env 不入库；
    URL/key 留空 = 该依赖未配置（技术选型「空值即关闭/降级」）。
    """

    model_config = SettingsConfigDict(
        env_prefix="quantabot_",
        env_file=str(PROJECT_ROOT / ".env"),  # 锚定项目根，不随 cwd 漂移
        env_file_encoding="utf-8",
        extra="ignore",
    )

    # 运行环境：dev=本地开发；prod=生产。仅作标签供 /health 与日志展示
    app_env: Literal["dev", "prod"] = "dev"
    # 服务监听地址与端口（端口为 Agent 自有配置，默认 8000，非主服务联调契约）
    host: str = "127.0.0.1"
    port: int = 8000
    # 全 fake 开关：True=全内存（本地/测试默认）；False=按各依赖配置逐项真接（缺配置即降级）
    fake_mode: bool = True
    # 决策日志 SQLite 路径（相对路径按 PROJECT_ROOT 解析，见 resolve_data_path）
    audit_db_path: str = "data/decisions.db"

    # ---- M2 真依赖（Tranche A）----
    # Redis（幂等/控制面/成本键；生产用主服务共用实例；本地 compose 用 redis://agent-redis:6379/0）
    redis_url: str = ""
    # DeepSeek（OpenAI 兼容端点；key 只进 .env，禁止入库/入日志）
    deepseek_base_url: str = "https://api.deepseek.com"
    deepseek_api_key: str = ""
    deepseek_model: str = "deepseek-chat"
    # Langfuse 自托管（本地 compose 容器网络内为 http://langfuse-web:3000）
    langfuse_host: str = "http://127.0.0.1:3000"
    langfuse_public_key: str = ""
    langfuse_secret_key: str = ""
    # Qdrant（M3 用户记忆与 RAG 内容索引使用；/health 保留连通检查）
    qdrant_url: str = "http://127.0.0.1:6333"

    # ---- Tranche B [Phase 0 契约已对齐 C-1/C-2/C-5]（真接线见 Task 15/16；token/user_id 已于 Task 14 增）----
    # [Phase 0 已对齐 C-1] RabbitMQ（本地 compose：amqp://quantabot:quantabot-dev@127.0.0.1:5672/）
    mq_url: str = ""
    # [Phase 0 已对齐 C-2/C-5] 主服务同步接口基址（评论树/检索/审核/写库）
    main_service_base_url: str = ""
    # [Phase 0 已对齐 C-5] 主服务 service token（demo0 签发的长期 JWT；只进 .env 不入库）
    main_service_token: str = ""
    # [Phase 0 已对齐 C-5] bot 系统账号 user_id（demo0 seed 后回填实际值；0=未配置）
    main_service_bot_user_id: int = 0

    # ---- 超时档（AGENTS Do：分设超时；Don't：全链路一个超时；熔断三态 M5）----
    llm_timeout_seconds: float = 60.0
    main_service_timeout_seconds: float = 10.0
    redis_timeout_seconds: float = 2.0
    langfuse_timeout_seconds: float = 5.0

    # ---- 控制面与成本（G5/G6）----
    # 控制面轮询间隔（秒）——G5 要求 ≤5s；kill 置位后最长该间隔内生效
    control_plane_poll_seconds: int = 5
    # 成本键 EXPIRE（小时）——G6：48h 防残留
    cost_key_ttl_hours: int = 48
    # LLM 计费单价（元/百万 token；技术选型 §4.3 DeepSeek V4-Pro 口径，可随价格调整）
    llm_input_price_per_mtok: float = 12.0
    llm_output_price_per_mtok: float = 24.0

    # ---- M3 记忆/RAG（Tranche 2 真接；空值即 fake/降级）----
    # Qdrant collection（M2 仅 /health 探测；M3 起承载用户记忆与 RAG 内容）
    qdrant_memory_collection: str = "qb_memory"
    qdrant_content_collection: str = "qb_content"
    # Qwen embedding（OpenAI 兼容端点；与主服务 QwenEmbeddingConfig 同源 1024 维——
    # [联调校准点] base_url/model 与 demo0 application.yml 对齐后才算配置完成）
    embedding_base_url: str = ""
    embedding_api_key: str = ""
    embedding_model: str = ""
    embedding_dim: int = 1024
    embedding_timeout_seconds: float = 10.0
    admin_token: str = ""  # /admin/ingest 可选校验（空=不校验，默认 127.0.0.1 绑定已限内网）
    # 记忆召回参数（粗召回 top-k 与精选上限——精选 ≤3 是蓝图钉死的值）
    memory_recall_top_k: int = 8
    memory_select_max: int = 3
    # 对话级记忆 TTL（小时）——短期状态存储过期即焚
    dialogue_memory_ttl_hours: int = 48
    # 远区摘要缓存 TTL（小时）——同帖第二条 @ 复用
    summary_cache_ttl_hours: int = 24
    # RAG 检索片段数上限（计入 RESERVED_BUDGET 预留预算）
    rag_fragment_limit: int = 3
