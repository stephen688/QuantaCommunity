"""按 Git 变更路径判断是否需要付费的 M4 Persona 门禁。

本模块只读取 ``git diff --name-only`` 的路径列表，不读取环境变量中的密钥、文件
内容或评测结果。无法确定基线时返回 ``persona_required=true``，把评测责任交给
维护者，而不是把不确定性伪装成 Fast gate 通过。
"""

from __future__ import annotations

import argparse
import subprocess
import sys
from pathlib import Path
from typing import Sequence

PROJECT_ROOT = Path(__file__).resolve().parents[1]

# 这些目录/关键词改变了人格、决策或安全边界，必须进入付费 Persona 档。
_SENSITIVE_DIRECTORY_NAMES = frozenset(
    {
        "context",
        "decision",
        "eval",
        "idempotency",
        "memory",
        "moderation",
        "prompts",
    }
)


def _normalise_path(changed_path: str) -> str:
    """把 Git 输出归一化为仓库相对路径，兼容 Windows 分隔符。"""

    normalised_path = changed_path.strip().replace("\\", "/")
    while normalised_path.startswith("./"):
        normalised_path = normalised_path[2:]

    # workflow 在 QuantaBot/ 目录执行，测试和人工调用也可能从仓库根运行。
    if normalised_path.startswith("QuantaBot/"):
        normalised_path = normalised_path[len("QuantaBot/") :]
    return normalised_path


def _is_documentation_only_path(changed_path: str) -> bool:
    """文档改动不触发付费档，即使文件名包含领域关键词。"""

    normalised_path = _normalise_path(changed_path)
    return normalised_path.startswith("docs/") and normalised_path.lower().endswith(".md")


def _is_sensitive_path(changed_path: str) -> bool:
    """判断一个仓库相对路径是否影响 M4 评测关注的行为边界。"""

    normalised_path = _normalise_path(changed_path)
    path_parts = tuple(part.lower() for part in normalised_path.split("/") if part)
    if not path_parts:
        return False

    # prompts 与 eval 是数据边界，整个目录都必须触发 Persona。
    if path_parts[0] in {"eval", "prompts"}:
        return True
    if len(path_parts) >= 2 and path_parts[0] == "tests" and path_parts[1] == "eval":
        return True

    # 生产 Python 文件即使暂时未命中已知关键词，也按未知行为变化保守处理。
    if len(path_parts) >= 2 and path_parts[0] == "src" and path_parts[1] == "quanta_bot":
        return path_parts[-1].endswith(".py")

    # 允许目录结构变化：例如 pipeline/context.py、crosscutting/moderation.py，
    # 以及未来将关键词拆成子目录时，仍然不能绕过付费门禁。
    return any(part in _SENSITIVE_DIRECTORY_NAMES for part in path_parts)


def classify_paths(changed_paths: Sequence[str]) -> bool:
    """返回路径集合是否要求 Persona gate。

    空集合表示没有可判断的代码变化，按 Fast gate 处理；基线是否存在由
    :func:`determine_persona_required` 单独决定。
    """

    normalised_paths = tuple(_normalise_path(path) for path in changed_paths if path.strip())
    if not normalised_paths:
        return False
    if all(_is_documentation_only_path(path) for path in normalised_paths):
        return False
    return any(_is_sensitive_path(path) for path in normalised_paths)


def determine_persona_required(base_sha: str | None, changed_paths: Sequence[str]) -> bool:
    """在无法确认 base 时保守要求 Persona，否则按路径分类。"""

    if not base_sha or not base_sha.strip():
        return True
    return classify_paths(changed_paths)


def read_changed_paths(
    base_sha: str, head_sha: str, *, repository_root: Path = PROJECT_ROOT
) -> tuple[str, ...]:
    """读取两个提交之间的路径名；不读取 diff 内容。"""

    completed = subprocess.run(
        ["git", "diff", "--name-only", base_sha, head_sha],
        cwd=repository_root,
        check=True,
        capture_output=True,
        text=True,
    )
    return tuple(path for path in completed.stdout.splitlines() if path.strip())


def _build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description="判断 BASE_SHA..HEAD_SHA 是否需要付费 M4 Persona 门禁。"
    )
    parser.add_argument("base_sha", nargs="?", help="Git 基线 SHA；缺失时保守要求 Persona")
    parser.add_argument("head_sha", nargs="?", default="HEAD", help="Git 目标 SHA，默认 HEAD")
    return parser


def main(argv: Sequence[str] | None = None) -> int:
    """输出机器可读的 ``persona_required=true|false``。"""

    arguments = _build_parser().parse_args(argv)
    if not arguments.base_sha:
        print("persona_required=true")
        return 0

    try:
        changed_paths = read_changed_paths(arguments.base_sha, arguments.head_sha)
    except (OSError, subprocess.CalledProcessError) as error:
        # shallow clone 或无效 ref 时不能误放行；stderr 不包含 diff 或任何 secret。
        print(f"无法读取 Git 变更路径，保守要求 Persona gate: {error}", file=sys.stderr)
        print("persona_required=true")
        return 0

    persona_required = determine_persona_required(arguments.base_sha, changed_paths)
    print(f"persona_required={'true' if persona_required else 'false'}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
