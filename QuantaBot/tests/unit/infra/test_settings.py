"""Settings 配置收口的行为测试：默认值、环境变量覆盖、fake 开关。"""

from quanta_bot.infra.settings import Settings


def test_settings_defaults() -> None:
    """默认值：dev 环境、127.0.0.1:8000、fake_mode 开（M0/M1 全 fake）。"""
    s = Settings()
    assert s.app_env == "dev"
    assert s.host == "127.0.0.1"
    assert s.port == 8000
    assert s.fake_mode is True


def test_settings_env_override(monkeypatch) -> None:
    """QUANTABOT_ 前缀环境变量可覆盖默认值（pydantic-settings 契约）。"""
    monkeypatch.setenv("QUANTABOT_PORT", "9000")
    monkeypatch.setenv("QUANTABOT_FAKE_MODE", "false")
    s = Settings()
    assert s.port == 9000
    assert s.fake_mode is False
