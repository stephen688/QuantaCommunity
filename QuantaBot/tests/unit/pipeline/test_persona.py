"""persona 加载器测试：文件即人格、hash 即版本（改文件自动变版本——蓝图 §5.7）。"""

from pathlib import Path

from quanta_bot.pipeline.persona import PersonaLibrary


def _write_prompts(tmp_path: Path) -> Path:
    """测试辅助：写五个最小人格文件，返回 prompts 目录。"""
    prompts = tmp_path / "prompts"
    prompts.mkdir()
    (prompts / "kernel.md").write_text("内核：你是框框。", encoding="utf-8")
    (prompts / "mode_answer.md").write_text("模式：专业答疑。", encoding="utf-8")
    (prompts / "mode_banter.md").write_text("模式：生活玩梗。", encoding="utf-8")
    (prompts / "mode_emotion.md").write_text("模式：情绪陪伴。", encoding="utf-8")
    (prompts / "mode_governance.md").write_text("模式：治理。", encoding="utf-8")
    return prompts


def test_system_prompt_concatenates_kernel_and_mode(tmp_path: Path) -> None:
    """system_prompt = 内核 + 空行 + 模式文本（A 通道常驻形态）。"""
    lib = PersonaLibrary(_write_prompts(tmp_path))
    assert lib.system_prompt("专业答疑") == "内核：你是框框。\n\n模式：专业答疑。"


def test_persona_version_changes_when_file_edited(tmp_path: Path) -> None:
    """内容 hash 即版本：改任一文件 → persona_version 变（12 位 hex；摘要缓存/记忆隔离随之失效）。"""
    prompts = _write_prompts(tmp_path)
    before = PersonaLibrary(prompts).persona_version
    (prompts / "kernel.md").write_text("内核：你是框框！（改动）", encoding="utf-8")
    after = PersonaLibrary(prompts).persona_version
    assert before != after and len(after) == 12


def test_missing_mode_file_raises(tmp_path: Path) -> None:
    """人格不完整 = 构造即抛（宁可启动失败不可人格残缺上线）。"""
    prompts = _write_prompts(tmp_path)
    (prompts / "mode_emotion.md").unlink()
    try:
        PersonaLibrary(prompts)
        raise AssertionError("应当抛 FileNotFoundError")
    except FileNotFoundError:
        pass


def test_default_library_loads_repo_prompts_dir() -> None:
    """锚定真实人格文件入库：无参默认读 PROJECT_ROOT/prompts，五文件齐、四模式可用。"""
    lib = PersonaLibrary()
    for mode in ("专业答疑", "生活玩梗", "情绪陪伴", "治理"):
        assert "框框" in lib.system_prompt(mode)
    assert len(lib.persona_version) == 12


def test_banter_prompt_preserves_short_compound_catchphrases() -> None:
    """接复合梗时不拆掉用户原话的识别点，确保读者一眼看出接住了哪个梗。"""
    prompt = PersonaLibrary().system_prompt("生活玩梗")

    assert "短复合梗" in prompt
    assert "第一句必须原样完整复用一次" in prompt
    assert "输出前检查" in prompt


def test_emotion_prompt_has_an_executable_brevity_budget() -> None:
    """“极短”必须转成模型可检查的硬预算，避免安慰回复扩成三段说教。"""
    prompt = PersonaLibrary().system_prompt("情绪陪伴")

    assert "不超过 3 句" in prompt
    assert "正文不超过 65 个汉字" in prompt
    assert "只给一个小动作" in prompt
    assert "共情句与记忆句不得重复同义" in prompt
