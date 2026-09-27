"""reconcile_cost 单测：差异判定、Langfuse v4 observations 分页和缺失样本。"""

import sys
from datetime import UTC, date, datetime
from pathlib import Path
from types import SimpleNamespace

PROJECT_ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(PROJECT_ROOT / "scripts"))

from reconcile_cost import (  # noqa: E402
    compare_costs,
    fetch_langfuse_cost_li,
    fetch_langfuse_cost_stats,
)


def test_compare_costs_within_tolerance() -> None:
    verdict = compare_costs(redis_cost_li=10000, langfuse_cost_li=9700, tolerance_pct=5.0)
    assert verdict["within_tolerance"] is True
    assert verdict["diff_li"] == 300


def test_compare_costs_exceeds_tolerance() -> None:
    verdict = compare_costs(redis_cost_li=10000, langfuse_cost_li=8000, tolerance_pct=5.0)
    assert verdict["within_tolerance"] is False


def test_compare_costs_zero_sides_do_not_divide_by_zero() -> None:
    verdict = compare_costs(redis_cost_li=0, langfuse_cost_li=0, tolerance_pct=5.0)
    assert verdict["within_tolerance"] is True
    assert verdict["diff_pct"] == 0.0


class _Observation:
    def __init__(
        self,
        name: str,
        metadata: dict | None,
        *,
        is_root_observation: bool | None = None,
        parent_observation_id: str | None = None,
    ) -> None:
        self.name = name
        self.metadata = metadata
        self.is_root_observation = is_root_observation
        self.parent_observation_id = parent_observation_id


class _Page:
    def __init__(self, data: list[_Observation], cursor: str | None) -> None:
        self.data = data
        self.meta = SimpleNamespace(cursor=cursor)


class _StubObservations:
    """Langfuse v4 get_many stub：3 条首批 + 1 条尾批。"""

    def __init__(self) -> None:
        self.pages = [
            _Page(
                [
                    _Observation("pipeline.run", {"cost_li": 100}, is_root_observation=True),
                    _Observation("generation", {"cost_li": 999}, is_root_observation=False),
                    _Observation("pipeline.run", {"cost_li": 200}, is_root_observation=True),
                ],
                "cursor-2",
            ),
            _Page(
                [_Observation("pipeline.run", {"cost_li": 50}, is_root_observation=True)],
                None,
            ),
        ]
        self.calls: list[dict] = []

    def get_many(self, **kwargs):
        self.calls.append(kwargs)
        return self.pages[len(self.calls) - 1]


class _StubLangfuse:
    def __init__(self) -> None:
        self.api = SimpleNamespace(observations=_StubObservations())


def test_fetch_langfuse_cost_sums_all_v4_observation_pages() -> None:
    client = _StubLangfuse()
    total = fetch_langfuse_cost_li(client, date(2026, 9, 25))
    assert total == 350
    calls = client.api.observations.calls
    assert len(calls) == 2
    assert calls[0]["name"] == "pipeline.run"
    assert calls[0]["is_root_observation"] is True
    assert calls[0]["from_start_time"] == datetime(2026, 9, 25, tzinfo=UTC)
    assert calls[1]["cursor"] == "cursor-2"


def test_missing_cost_is_allowed_only_for_proven_no_generation_root() -> None:
    """合法 skipped 缺少生成费用可按零；replied 缺费用必须暴露为缺失。"""
    client = _StubLangfuse()
    client.api.observations.pages = [
        _Page(
            [
                _Observation(
                    "pipeline.run",
                    {
                        "decision": "skipped_decision",
                        "generated_content": None,
                        "stage_ms": {"decision": 3},
                    },
                    is_root_observation=True,
                ),
                _Observation(
                    "pipeline.run",
                    {
                        "decision": "replied",
                        "generated_content": "reply",
                        "stage_ms": {"decision": 3, "generation": 8},
                        "cost_li": None,
                    },
                    is_root_observation=True,
                ),
            ],
            None,
        )
    ]
    stats = fetch_langfuse_cost_stats(client, date(2026, 9, 25))
    assert stats["sample_count"] == 2
    assert stats["generation_sample_count"] == 1
    assert stats["no_generation_sample_count"] == 1
    assert stats["missing_cost_count"] == 1
    fresh_client = _StubLangfuse()
    fresh_client.api.observations.pages = client.api.observations.pages
    assert fetch_langfuse_cost_li(fresh_client, date(2026, 9, 25)) == 0


def test_unknown_root_without_generation_evidence_is_not_counted_as_zero() -> None:
    """历史 trace 缺少可证明的生成状态时，即使 cost_li=None 也必须拒绝对账。"""
    client = _StubLangfuse()
    client.api.observations.pages = [
        _Page(
            [
                _Observation(
                    "pipeline.run",
                    {"decision": "failed", "cost_li": None},
                    is_root_observation=True,
                )
            ],
            None,
        )
    ]
    stats = fetch_langfuse_cost_stats(client, date(2026, 9, 25))
    assert stats["unknown_state_count"] == 1
    assert stats["missing_cost_count"] == 1


def test_replied_decision_wins_over_empty_stage_map() -> None:
    """旧/压缩 trace 即使 stage_ms 为空，replied 仍证明生成路径已完成。"""
    client = _StubLangfuse()
    client.api.observations.pages = [
        _Page(
            [
                _Observation(
                    "pipeline.run",
                    {"decision": "replied", "stage_ms": {}, "cost_li": None},
                    is_root_observation=True,
                )
            ],
            None,
        )
    ]
    stats = fetch_langfuse_cost_stats(client, date(2026, 9, 25))
    assert stats["generation_sample_count"] == 1
    assert stats["no_generation_sample_count"] == 0
    assert stats["missing_cost_count"] == 1


def test_incomplete_generation_usage_keeps_known_cost_but_blocks_reconciliation() -> None:
    """partial usage 保留已知成本，同时不能被对账误判为完整。"""
    client = _StubLangfuse()
    client.api.observations.pages = [
        _Page(
            [
                _Observation(
                    "pipeline.run",
                    {
                        "decision": "failed",
                        "generated_content": None,
                        "stage_ms": {"generation": 8},
                        "cost_li": 8,
                        "generation_usage_complete": False,
                    },
                    is_root_observation=True,
                )
            ],
            None,
        )
    ]

    stats = fetch_langfuse_cost_stats(client, date(2026, 9, 25))

    assert stats["total_cost_li"] == 8
    assert stats["generation_sample_count"] == 1
    assert stats["missing_cost_count"] == 1
    assert "generation_usage_complete" in client.api.observations.calls[0]["expand_metadata"]
