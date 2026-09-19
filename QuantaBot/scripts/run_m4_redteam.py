"""在 uv 环境中跨平台启动 Promptfoo M4 红队。"""

from __future__ import annotations

import json
import os
import shutil
import subprocess
import sys
from pathlib import Path

import yaml

from quanta_bot.infra.settings import Settings

PROJECT_ROOT = Path(__file__).resolve().parents[1]
_REDTEAM_CONFIG = PROJECT_ROOT / "eval/redteam.yaml"
_GENERATED_CASES = PROJECT_ROOT / "eval/m4-v1-redteam-generated.yaml"
_RAW_RESULTS = PROJECT_ROOT / "eval/reports/raw/m4-v1-redteam-results.json"
_GATE_MANIFEST = PROJECT_ROOT / "eval/gate-manifest.yaml"


def _frozen_model() -> str:
    manifest = yaml.safe_load(_GATE_MANIFEST.read_text(encoding="utf-8"))
    return str(manifest["model"])


def _scrub_account_metadata(raw_path: Path) -> None:
    """移除 Promptfoo 账户标识；保留合成攻击样本供受限 artifact 审计。"""
    if not raw_path.exists():
        return
    payload = json.loads(raw_path.read_text(encoding="utf-8"))
    metadata = payload.get("metadata")
    if isinstance(metadata, dict) and "author" in metadata:
        metadata["author"] = "[redacted]"
    raw_path.write_text(
        json.dumps(payload, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )


def main(extra_args: list[str] | None = None) -> int:
    """把当前 uv Python 交给 Promptfoo worker，并透传额外 CLI 参数。"""
    npx = shutil.which("npx")
    if npx is None:
        print("M4 redteam requires Node.js and npx on PATH", file=sys.stderr)
        return 2

    settings = Settings()
    if not settings.deepseek_api_key:
        print("M4 redteam requires QUANTABOT_DEEPSEEK_API_KEY", file=sys.stderr)
        return 2
    frozen_model = _frozen_model()
    if settings.deepseek_model != frozen_model:
        print(
            "M4 redteam target model drift: "
            f"settings={settings.deepseek_model!r}, manifest={frozen_model!r}",
            file=sys.stderr,
        )
        return 2

    _GENERATED_CASES.parent.mkdir(parents=True, exist_ok=True)
    env = os.environ.copy()
    env["PROMPTFOO_PYTHON"] = sys.executable
    # Promptfoo 0.123.1 的 OpenAI-compatible provider 只读取 OPENAI_*；显式映射到
    # QuantaBot 已冻结的 DeepSeek 配置，避免攻击生成/红队 grader 回退第二供应商。
    env["OPENAI_API_KEY"] = settings.deepseek_api_key
    env["OPENAI_API_BASE_URL"] = settings.deepseek_base_url
    # DeepSeek reasoning tokens count against Promptfoo's OpenAI-compatible default of
    # 1024, which can leave no room for the rubric JSON and create false red-team fails.
    env["OPENAI_MAX_TOKENS"] = "4096"
    args = extra_args if extra_args is not None else sys.argv[1:]
    generation_args = [arg for arg in args if arg in {"--force"}]
    evaluation_args = [arg for arg in args if arg not in {"--strict", "--force"}]
    generate_command = [
        npx,
        "promptfoo",
        "redteam",
        "generate",
        "--config",
        str(_REDTEAM_CONFIG),
        "--output",
        str(_GENERATED_CASES),
        "--no-cache",
        "--remote",
        "--strict",
        *generation_args,
    ]
    generated = subprocess.run(
        generate_command,
        cwd=PROJECT_ROOT,
        env=env,
        check=False,
    )
    if generated.returncode != 0:
        return generated.returncode

    _RAW_RESULTS.parent.mkdir(parents=True, exist_ok=True)
    evaluate_command = [
        npx,
        "promptfoo",
        "eval",
        "--config",
        str(_GENERATED_CASES),
        "--output",
        str(_RAW_RESULTS),
        "--no-cache",
        "--remote",
        "--max-concurrency",
        "1",
        "--delay",
        "1000",
        "--no-progress-bar",
        "--no-table",
        *evaluation_args,
    ]
    evaluated = subprocess.run(
        evaluate_command,
        cwd=PROJECT_ROOT,
        env=env,
        check=False,
    )
    _scrub_account_metadata(_RAW_RESULTS)
    return evaluated.returncode


if __name__ == "__main__":
    raise SystemExit(main())
