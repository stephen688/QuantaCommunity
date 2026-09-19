"""M4 Promptfoo 跨平台启动器测试。"""

import sys
from pathlib import Path
from types import SimpleNamespace

from scripts import run_m4_redteam


def test_launcher_uses_uv_python_and_separate_raw_output(monkeypatch, tmp_path: Path) -> None:
    """Node 进程必须把当前 uv Python 交给 worker，且不得覆盖源 redteam.yaml。"""
    captured: dict[str, object] = {}

    monkeypatch.setattr(run_m4_redteam.shutil, "which", lambda command: "C:/node/npx.cmd")

    def fake_run(command, *, env, check):
        captured.update(command=command, env=env, check=check)
        return SimpleNamespace(returncode=0)

    monkeypatch.setattr(run_m4_redteam.subprocess, "run", fake_run)
    monkeypatch.chdir(tmp_path)

    exit_code = run_m4_redteam.main(["--strict", "--tag", "gate=m4-v1"])

    assert exit_code == 0
    assert captured["env"]["PROMPTFOO_PYTHON"] == sys.executable
    assert captured["command"][:4] == [
        "C:/node/npx.cmd",
        "promptfoo",
        "redteam",
        "run",
    ]
    assert "eval/redteam.yaml" in captured["command"]
    assert "eval/m4-v1-redteam-generated.yaml" in captured["command"]
    assert captured["command"][-3:] == ["--strict", "--tag", "gate=m4-v1"]
    assert captured["check"] is False


def test_launcher_fails_clearly_without_npx(monkeypatch) -> None:
    monkeypatch.setattr(run_m4_redteam.shutil, "which", lambda command: None)

    assert run_m4_redteam.main([]) == 2
