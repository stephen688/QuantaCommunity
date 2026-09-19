"""在 uv 环境中跨平台启动 Promptfoo M4 红队。"""

from __future__ import annotations

import os
import shutil
import subprocess
import sys
from pathlib import Path

from quanta_bot.infra.settings import Settings

_GENERATED_CASES = "eval/m4-v1-redteam-generated.yaml"


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

    Path(_GENERATED_CASES).parent.mkdir(parents=True, exist_ok=True)
    env = os.environ.copy()
    env["PROMPTFOO_PYTHON"] = sys.executable
    # Promptfoo 0.123.1 的 OpenAI-compatible provider 只读取 OPENAI_*；显式映射到
    # QuantaBot 已冻结的 DeepSeek 配置，避免攻击生成/红队 grader 回退第二供应商。
    env["OPENAI_API_KEY"] = settings.deepseek_api_key
    env["OPENAI_API_BASE_URL"] = settings.deepseek_base_url
    command = [
        npx,
        "promptfoo",
        "redteam",
        "run",
        "--config",
        "eval/redteam.yaml",
        "--output",
        _GENERATED_CASES,
        "--no-cache",
        "--remote",
        *(extra_args if extra_args is not None else sys.argv[1:]),
    ]
    return subprocess.run(command, env=env, check=False).returncode


if __name__ == "__main__":
    raise SystemExit(main())
