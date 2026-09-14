"""infra/settings —— 配置收口（全仓库唯一读取环境变量/.env 的模块）。

职责：定义 Settings 模型与默认值；业务模块只从这里拿配置，禁止直接触碰 os.environ。
边界：不建任何外部连接；不做依赖连通性检测（/health 负责呈现）；字段与 .env.example 一一对应。
"""

from typing import Literal

from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    """QuantaBot 运行配置（环境变量前缀 QUANTABOT_，支持 .env 文件）。"""

    model_config = SettingsConfigDict(
        env_prefix="quantabot_",
        env_file=".env",
        env_file_encoding="utf-8",
        extra="ignore",
    )

    # 运行环境：dev=本地开发；prod=生产。M0 仅作标签供 /health 与日志展示
    app_env: Literal["dev", "prod"] = "dev"
    # 服务监听地址与端口（端口为 Agent 自有配置，默认 8000，非主服务联调契约）
    host: str = "127.0.0.1"
    port: int = 8000
    # 全 fake 开关：M0/M1 默认开（外部依赖全走内存假实现），M2 起逐项替换真实现
    fake_mode: bool = True
