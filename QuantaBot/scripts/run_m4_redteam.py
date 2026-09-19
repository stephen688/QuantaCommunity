"""在 uv 环境中跨平台启动 Promptfoo M4 红队。"""

from __future__ import annotations

import os
import shutil
import subprocess
import sys
from pathlib import Path

_GENERATED_CASES = "eval/reports/raw/m4-v1-redteam-generated.yaml"


def main(extra_args: list[str] | None = None) -> int:
    """把当前 uv Python 交给 Promptfoo worker，并透传额外 CLI 参数。"""
    npx = shutil.which("npx")
    if npx is None:
        print("M4 redteam requires Node.js and npx on PATH", file=sys.stderr)
        return 2

    Path(_GENERATED_CASES).parent.mkdir(parents=True, exist_ok=True)
    env = os.environ.copy()
    env["PROMPTFOO_PYTHON"] = sys.executable
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
        *(extra_args if extra_args is not None else sys.argv[1:]),
    ]
    return subprocess.run(command, env=env, check=False).returncode


if __name__ == "__main__":
    raise SystemExit(main())
