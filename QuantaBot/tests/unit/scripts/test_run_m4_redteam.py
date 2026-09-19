"""M4 Promptfoo 跨平台启动器测试。"""

import sys
from pathlib import Path
from types import SimpleNamespace

from scripts import run_m4_redteam


def test_launcher_uses_uv_python_and_separate_raw_output(monkeypatch, tmp_path: Path) -> None:
    """Node 进程必须把当前 uv Python 交给 worker，且不得覆盖源 redteam.yaml。"""
    captured: dict[str, object] = {}

    monkeypatch.setattr(run_m4_redteam.shutil, "which", lambda command: "C:/node/npx.cmd")
    monkeypatch.setattr(
        run_m4_redteam,
        "Settings",
        lambda: SimpleNamespace(
            deepseek_api_key="test-deepseek-key",
            deepseek_base_url="https://api.deepseek.example",
            deepseek_model="deepseek-v4-flash",
        ),
    )

    def fake_run(command, *, cwd, env, check):
        captured.update(command=command, cwd=cwd, env=env, check=check)
        return SimpleNamespace(returncode=0)

    monkeypatch.setattr(run_m4_redteam.subprocess, "run", fake_run)
    monkeypatch.chdir(tmp_path)

    exit_code = run_m4_redteam.main(["--strict", "--tag", "gate=m4-v1"])

    assert exit_code == 0
    assert captured["env"]["PROMPTFOO_PYTHON"] == sys.executable
    assert captured["env"]["OPENAI_API_KEY"] == "test-deepseek-key"
    assert captured["env"]["OPENAI_API_BASE_URL"] == "https://api.deepseek.example"
    assert captured["env"]["OPENAI_MAX_TOKENS"] == "4096"
    assert captured["command"][:4] == [
        "C:/node/npx.cmd",
        "promptfoo",
        "redteam",
        "run",
    ]
    assert str(run_m4_redteam.PROJECT_ROOT / "eval/redteam.yaml") in captured["command"]
    assert (
        str(run_m4_redteam.PROJECT_ROOT / "eval/m4-v1-redteam-generated.yaml")
        in captured["command"]
    )
    assert "--remote" in captured["command"]
    assert captured["command"][-3:] == ["--strict", "--tag", "gate=m4-v1"]
    assert captured["cwd"] == run_m4_redteam.PROJECT_ROOT
    assert captured["check"] is False


def test_launcher_fails_clearly_without_npx(monkeypatch) -> None:
    monkeypatch.setattr(run_m4_redteam.shutil, "which", lambda command: None)

    assert run_m4_redteam.main([]) == 2


def test_launcher_rejects_target_model_drift(monkeypatch) -> None:
    """目标模型必须与冻结 manifest 一致，避免 target/Judge 偷偷分叉。"""
    monkeypatch.setattr(run_m4_redteam.shutil, "which", lambda command: "npx")
    monkeypatch.setattr(
        run_m4_redteam,
        "Settings",
        lambda: SimpleNamespace(
            deepseek_api_key="test-key",
            deepseek_base_url="https://api.deepseek.example",
            deepseek_model="deepseek-chat",
        ),
    )

    assert run_m4_redteam.main([]) == 2
