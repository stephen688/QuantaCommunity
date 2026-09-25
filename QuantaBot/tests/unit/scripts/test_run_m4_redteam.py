"""M4 Promptfoo 跨平台启动器测试。"""

import json
import sys
from pathlib import Path
from types import SimpleNamespace

from scripts import run_m4_redteam


def test_launcher_uses_uv_python_and_separate_raw_output(monkeypatch, tmp_path: Path) -> None:
    """Node 进程必须把当前 uv Python 交给 worker，且不得覆盖源 redteam.yaml。"""
    captured: list[dict[str, object]] = []

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
        captured.append({"command": command, "cwd": cwd, "env": env, "check": check})
        return SimpleNamespace(returncode=0)

    monkeypatch.setattr(run_m4_redteam.subprocess, "run", fake_run)
    monkeypatch.chdir(tmp_path)

    exit_code = run_m4_redteam.main(["--strict", "--tag", "gate=m4-v1"])

    assert exit_code == 0
    assert len(captured) == 2
    generate, evaluate = captured
    assert generate["env"]["PROMPTFOO_PYTHON"] == sys.executable
    assert generate["env"]["OPENAI_API_KEY"] == "test-deepseek-key"
    assert generate["env"]["OPENAI_API_BASE_URL"] == "https://api.deepseek.example"
    assert generate["env"]["OPENAI_MAX_TOKENS"] == "4096"
    assert generate["command"][:4] == [
        "C:/node/npx.cmd",
        "promptfoo",
        "redteam",
        "generate",
    ]
    assert str(run_m4_redteam.PROJECT_ROOT / "eval/redteam.yaml") in generate["command"]
    assert (
        str(run_m4_redteam.PROJECT_ROOT / "eval/m4-v1-redteam-generated.yaml")
        in generate["command"]
    )
    assert "--strict" in generate["command"]
    assert "--remote" in generate["command"]
    assert evaluate["command"][:3] == ["C:/node/npx.cmd", "promptfoo", "eval"]
    assert (
        str(run_m4_redteam.PROJECT_ROOT / "eval/m4-v1-redteam-generated.yaml")
        in evaluate["command"]
    )
    assert (
        str(run_m4_redteam.PROJECT_ROOT / "eval/reports/raw/m4-v1-redteam-results.json")
        in (evaluate["command"])
    )
    assert evaluate["command"][-2:] == ["--tag", "gate=m4-v1"]
    assert generate["cwd"] == run_m4_redteam.PROJECT_ROOT
    assert evaluate["cwd"] == run_m4_redteam.PROJECT_ROOT
    assert generate["check"] is False
    assert evaluate["check"] is False


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


def test_scrub_raw_results_removes_promptfoo_account_email_only(tmp_path: Path) -> None:
    raw = tmp_path / "raw.json"
    raw.write_text(
        json.dumps(
            {
                "metadata": {"author": "private@example.com"},
                "results": {"results": [{"vars": {"prompt": "synthetic@example.test"}}]},
            }
        ),
        encoding="utf-8",
    )

    run_m4_redteam._scrub_account_metadata(raw)

    payload = json.loads(raw.read_text(encoding="utf-8"))
    assert payload["metadata"]["author"] == "[redacted]"
    assert payload["results"]["results"][0]["vars"]["prompt"] == "synthetic@example.test"
