"""pipeline/persona —— 人格文件加载（prompts/ 是数据不是代码，AGENTS.md §2）。

职责：读 prompts/ 五文件（kernel + 四模式）；persona_version = 全部文件内容 sha256 前 12 位
      （改文件自动变版本——摘要缓存/记忆隔离随之失效，蓝图 §5.7 条件指纹）；
      system_prompt(mode) = kernel + 模式文件（A 通道：常驻 system prompt，不占预算、永不截断）。
边界：不做模式路由（decision 层负责）；不校验文本内容质量（管理员审定 + eval 行为验证）；
      文件缺失 = 人格不完整 = 构造即抛（宁可启动失败不可人格残缺上线）。
"""

import hashlib
from pathlib import Path

from quanta_bot.infra.settings import PROJECT_ROOT

# 四模式 → 文件名（PRD F3；模式名与 decision.Mode 枚举一致）
MODE_FILES: dict[str, str] = {
    "专业答疑": "mode_answer.md",
    "生活玩梗": "mode_banter.md",
    "情绪陪伴": "mode_emotion.md",
    "治理": "mode_governance.md",
}
KERNEL_FILE = "kernel.md"


class PersonaLibrary:
    """人格库（启动时一次读入；文件变更需重启进程——人格热更新不在 M3 范围）。"""

    def __init__(self, prompts_dir: Path | None = None) -> None:
        self._dir = prompts_dir or (PROJECT_ROOT / "prompts")
        self._kernel = self._read(KERNEL_FILE)
        self._modes = {mode: self._read(name) for mode, name in MODE_FILES.items()}
        digest = hashlib.sha256(
            (self._kernel + "".join(self._modes[m] for m in sorted(self._modes))).encode("utf-8")
        ).hexdigest()
        self._version = digest[:12]

    @property
    def persona_version(self) -> str:
        """内容 hash 版本号（M3 记忆 payload / 摘要缓存 key 的条件指纹）。"""
        return self._version

    def system_prompt(self, mode: str) -> str:
        """A 通道：内核 + 模式 prompt（常驻 system，永不截断）。"""
        if mode not in self._modes:
            raise KeyError(f"未知模式：{mode}（合法值：{sorted(self._modes)}）")
        return f"{self._kernel}\n\n{self._modes[mode]}"

    def _read(self, name: str) -> str:
        path = self._dir / name
        if not path.is_file():
            raise FileNotFoundError(f"人格文件缺失：{path}（人格不完整不可启动）")
        return path.read_text(encoding="utf-8").strip()
