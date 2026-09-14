"""Settings 配置收口的行为测试：默认值、环境变量覆盖、fake 开关、审计库路径。"""

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
