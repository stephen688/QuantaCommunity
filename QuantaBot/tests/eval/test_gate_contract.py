"""M4 gate 契约：冻结集合、阈值和内容指纹；不执行真实模型调用。"""

from pathlib import Path

from tests.eval._gate import load_gate_manifest, sha256_files
from tests.eval._runner import CASES_DIR, load_case

from quanta_bot.pipeline.persona import PersonaLibrary

PROJECT_ROOT = Path(__file__).resolve().parents[2]
MANIFEST_PATH = PROJECT_ROOT / "eval" / "gate-manifest.yaml"
EXPECTED_PERSONA_CASE_IDS = (
    "persona-01-course",
    "persona-02-compare",
    "persona-03-exam",
    "persona-04-verify",
    "persona-05-summarize",
    "persona-06-policy",
    "persona-07-judge",
    "persona-08-meme",
    "persona-09-supplement",
    "persona-10-roast",
    "persona-11-comfort",
    "persona-12-fail",
    "persona-13-joy",
    "persona-14-injection",
    "persona-15-flamewar",
    "persona-16-offer",
    "persona-17-referral",
)


def test_gate_manifest_freezes_exactly_seventeen_persona_cases() -> None:
    manifest = load_gate_manifest(MANIFEST_PATH)

    assert manifest.version == "m4-v1"
    assert manifest.model == "deepseek-v4-flash"
    assert manifest.case_ids == EXPECTED_PERSONA_CASE_IDS
    assert len(manifest.case_ids) == len(set(manifest.case_ids)) == 17
    assert tuple(case_id.split("-", 2)[1] for case_id in manifest.case_ids) == tuple(
        f"{number:02d}" for number in range(1, 18)
    )
    assert manifest.emotion_case_ids == (
        "persona-11-comfort",
        "persona-12-fail",
        "persona-13-joy",
    )


def test_gate_manifest_references_existing_persona_cases_with_valid_thresholds() -> None:
    manifest = load_gate_manifest(MANIFEST_PATH)

    loaded_cases = [load_case(CASES_DIR / f"{case_id}.yaml") for case_id in manifest.case_ids]
    assert all(case.tier == "persona" for case in loaded_cases)
    assert 1 <= manifest.normal_min_score <= 5
    assert 1 <= manifest.emotion_min_score <= 5
    assert 0 <= manifest.judge_score_delta_max <= 4
    assert manifest.max_infra_retries == 1
    assert manifest.required_p0_assertions == (
        "decision_is",
        "mode_is",
        "reply_has_ai_badge",
        "reply_no_secret_leak",
    )


def test_sha256_files_is_order_independent_and_content_sensitive(tmp_path: Path) -> None:
    first = tmp_path / "first.txt"
    second = tmp_path / "second.txt"
    first.write_text("alpha", encoding="utf-8")
    second.write_text("beta", encoding="utf-8")

    original = sha256_files([second, first])

    assert original == sha256_files([first, second])
    first.write_text("changed", encoding="utf-8")
    assert original != sha256_files([first, second])


def test_persona_version_changes_when_prompt_content_changes(tmp_path: Path) -> None:
    source_prompts = PROJECT_ROOT / "prompts"
    for source in source_prompts.glob("*.md"):
        (tmp_path / source.name).write_bytes(source.read_bytes())
    original_version = PersonaLibrary(tmp_path).persona_version

    kernel = tmp_path / "kernel.md"
    kernel.write_text(kernel.read_text(encoding="utf-8") + "\n冻结指纹测试", encoding="utf-8")

    assert PersonaLibrary(tmp_path).persona_version != original_version
