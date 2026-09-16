"""Settings 配置收口的行为测试：默认值、环境变量覆盖、fake 开关、审计库路径。"""

from pathlib import Path

from quanta_bot.infra.settings import Settings


def test_settings_defaults() -> None:
    """默认值：dev 环境、127.0.0.1:8000、fake_mode 开（M0/M1 全 fake）。

    用 _env_file=None 隔离本地 .env（M0 审查 Minor-1 测试纯净度）。
    """
    s = Settings(_env_file=None)
    assert s.app_env == "dev"
    assert s.host == "127.0.0.1"
    assert s.port == 8000
    assert s.fake_mode is True


def test_settings_env_override(monkeypatch) -> None:
    """QUANTABOT_ 前缀环境变量可覆盖默认值（pydantic-settings 契约）。"""
    # 计划 Step 1.2 注记：先清 shell 可能残留的前缀变量，防本地环境污染断言（M0 审查 Minor-1 延续）
    monkeypatch.delenv("QUANTABOT_PORT", raising=False)
    monkeypatch.delenv("QUANTABOT_FAKE_MODE", raising=False)
    monkeypatch.setenv("QUANTABOT_PORT", "9000")
    monkeypatch.setenv("QUANTABOT_FAKE_MODE", "false")
    s = Settings(_env_file=None)
    assert s.port == 9000
    assert s.fake_mode is False


def test_settings_audit_db_path_default(monkeypatch) -> None:
    """决策日志 SQLite 路径默认 data/decisions.db，可用环境变量覆盖。"""
    s = Settings(_env_file=None)
    assert s.audit_db_path == "data/decisions.db"
    monkeypatch.setenv("QUANTABOT_AUDIT_DB_PATH", "d:/tmp/x.db")
    assert Settings(_env_file=None).audit_db_path == "d:/tmp/x.db"


def test_settings_m2_real_dep_defaults() -> None:
    """M2 真依赖默认值：空 URL=未配置（空值即关闭），超时档分设。"""
    s = Settings(_env_file=None)
    assert s.redis_url == ""
    assert s.qdrant_url == "http://127.0.0.1:6333"
    assert s.mq_url == "" and s.main_service_base_url == ""
    assert s.deepseek_base_url == "https://api.deepseek.com"
    assert s.deepseek_api_key == "" and s.deepseek_model == "deepseek-chat"
    assert s.langfuse_host == "http://127.0.0.1:3000"
    assert s.langfuse_public_key == "" and s.langfuse_secret_key == ""
    assert s.llm_timeout_seconds == 60.0
    assert s.main_service_timeout_seconds == 10.0
    assert s.redis_timeout_seconds == 2.0
    assert s.langfuse_timeout_seconds == 5.0
    assert s.control_plane_poll_seconds == 5
    assert s.cost_key_ttl_hours == 48
    assert s.llm_input_price_per_mtok == 12.0
    assert s.llm_output_price_per_mtok == 24.0


def test_settings_m2_env_override(monkeypatch) -> None:
    """QUANTABOT_ 前缀可覆盖 M2 新字段。"""
    # 环境隔离纪律：清掉可能残留在真实环境的 QUANTABOT_* 变量（CI/本机 shell 导出的
    # 值会让「默认值/覆盖值」断言假红假绿）；新增 env 类用例同样先 delenv 再断言。
    monkeypatch.delenv("QUANTABOT_REDIS_URL", raising=False)
    monkeypatch.delenv("QUANTABOT_LLM_TIMEOUT_SECONDS", raising=False)
    monkeypatch.setenv("QUANTABOT_REDIS_URL", "redis://127.0.0.1:6379/0")
    monkeypatch.setenv("QUANTABOT_LLM_TIMEOUT_SECONDS", "30")
    s = Settings(_env_file=None)
    assert s.redis_url == "redis://127.0.0.1:6379/0"
    assert s.llm_timeout_seconds == 30.0


def test_env_file_anchored_to_project_root() -> None:
    """[M0 遗留 Minor-2 清账] env_file 锚定模块相对的项目根——不随 cwd 漂移。"""
    from quanta_bot.infra import settings as settings_module

    env_file = Path(settings_module.Settings.model_config["env_file"])
    assert env_file.is_absolute()
    assert env_file.name == ".env"
    assert (settings_module.PROJECT_ROOT / "pyproject.toml").exists()


def test_resolve_data_path_anchor() -> None:
    """相对路径锚到项目根；绝对路径原样返回（audit_db_path 落盘位置不随 cwd 漂移）。"""
    import sys

    from quanta_bot.infra.settings import PROJECT_ROOT, resolve_data_path

    assert resolve_data_path("data/decisions.db") == PROJECT_ROOT / "data" / "decisions.db"
    # 绝对路径样本按平台构造：Windows 盘符（D:/…）在 POSIX 上不是绝对路径，
    # 固定盘符样本会让 CI（Linux）误红（M2 push 首跑 CI 实抓）
    absolute = "D:/abs/x.db" if sys.platform == "win32" else "/abs/x.db"
    assert resolve_data_path(absolute) == Path(absolute)
