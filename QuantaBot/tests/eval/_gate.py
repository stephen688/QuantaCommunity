"""M4 gate：加载冻结策略并计算内容指纹。

本模块只定义跨 run 的稳定契约；单条 case 的执行和断言仍由 ``_runner`` 负责。
"""

import hashlib
from collections.abc import Sequence
from pathlib import Path

import yaml
from pydantic import BaseModel, Field, model_validator


class GateCasePolicy(BaseModel):
    """一条 Persona case 在门禁中的最低分和稳定性策略。"""

    case_id: str
    min_score: int = Field(ge=1, le=5)
    judge_score_delta_max: int = Field(ge=0, le=4)
    emotion: bool = False


class GateManifest(BaseModel):
    """版本控制的 M4 门禁集合与阈值。"""

    version: str
    model: str
    normal_min_score: int = Field(ge=1, le=5)
    emotion_min_score: int = Field(ge=1, le=5)
    judge_score_delta_max: int = Field(ge=0, le=4)
    max_infra_retries: int = Field(ge=0, le=1)
    case_ids: tuple[str, ...]
    emotion_case_ids: tuple[str, ...]
    required_p0_assertions: tuple[str, ...]

    @model_validator(mode="after")
    def validate_frozen_set(self) -> "GateManifest":
        """拒绝重复、悬空情绪项和非连续的 persona-01～17 集合。"""
        if len(self.case_ids) != 17 or len(set(self.case_ids)) != 17:
            raise ValueError("gate manifest 必须恰好包含 17 个不重复 case")
        expected_prefixes = tuple(f"persona-{number:02d}" for number in range(1, 18))
        actual_prefixes = tuple(case_id.rsplit("-", 1)[0] for case_id in self.case_ids)
        if actual_prefixes != expected_prefixes:
            raise ValueError("gate manifest case 必须按 persona-01～persona-17 连续排列")
        if not set(self.emotion_case_ids).issubset(self.case_ids):
            raise ValueError("emotion_case_ids 必须属于冻结 case 集")
        return self

    def policy_for(self, case_id: str) -> GateCasePolicy:
        """返回 case 的冻结阈值；未登记 case 不得进入付费门禁。"""
        if case_id not in self.case_ids:
            raise KeyError(f"case 未登记在 gate manifest：{case_id}")
        emotion = case_id in self.emotion_case_ids
        return GateCasePolicy(
            case_id=case_id,
            min_score=self.emotion_min_score if emotion else self.normal_min_score,
            judge_score_delta_max=self.judge_score_delta_max,
            emotion=emotion,
        )


def load_gate_manifest(path: Path) -> GateManifest:
    """从 YAML 加载门禁；结构或阈值漂移时立即拒绝。"""
    return GateManifest.model_validate(yaml.safe_load(path.read_text(encoding="utf-8")))


def sha256_files(paths: Sequence[Path]) -> str:
    """按规范化路径排序，对路径和文件内容共同计算稳定 SHA-256。"""
    digest = hashlib.sha256()
    for path in sorted(paths, key=lambda item: item.as_posix()):
        digest.update(path.as_posix().encode("utf-8"))
        digest.update(b"\0")
        digest.update(path.read_bytes())
        digest.update(b"\0")
    return digest.hexdigest()
