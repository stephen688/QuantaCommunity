"""memory/user_memory 行为测试：D 通道渲染（负面全量永不截断 + 精选丢末位留痕 + 分类型衰减警告）。"""

from datetime import UTC, datetime, timedelta

from quanta_bot.memory.ports import MemoryRecord
from quanta_bot.memory.user_memory import render_memory_block


def _record(
    user_id: int = 42, content: str = "用户在准备跨专业考研", **kwargs: object
) -> MemoryRecord:
    """测试辅助：支持 type_/days_ago 便捷参数（Task 7 渲染用例复用本 helper）。"""
    base: dict[str, object] = {
        "user_id": user_id,
        "type": "user",
        "content": content,
        "why": "画像：影响答疑口径",
        "created_at": datetime.now(UTC),
        "persona_version": "abc123",
    }
    if "type_" in kwargs:
        kwargs["type"] = kwargs.pop("type_")
    if "days_ago" in kwargs:
        # days_ago 便捷参数 → 绝对日期（渲染衰减警告用例需要"X 天前"的可控时间锚点）
        kwargs["created_at"] = datetime.now(UTC) - timedelta(days=int(kwargs.pop("days_ago")))  # type: ignore[arg-type]
    base.update(kwargs)
    return MemoryRecord(**base)  # type: ignore[arg-type]


def test_render_memory_block_negative_never_truncated() -> None:
    """负面偏好全量注入永不截断；精选超 select_max 丢末位并留痕。"""
    negatives = [
        _record(content=f"别推考研班{i}", type_="feedback", valence="negative") for i in range(5)
    ]
    selected = [_record(content=f"画像{i}", type_="user") for i in range(5)]
    text, truncations = render_memory_block(selected=selected, negatives=negatives, select_max=3)
    for i in range(5):
        assert f"别推考研班{i}" in text  # 负面 5 条全在
    assert "画像3" not in text and "画像4" not in text  # 精选丢末位
    assert any(t.channel == "D" for t in truncations)


def test_render_memory_block_decay_warning_by_type() -> None:
    """分类型衰减：project 15 天前→警告；user 100 天前→不警告（阈值 14/45/180）。"""
    old_project = _record(content="在准备毕设", type_="project", days_ago=15)
    stable_user = _record(content="跨专业考研", type_="user", days_ago=100)
    text, _ = render_memory_block(selected=[old_project, stable_user], negatives=(), select_max=3)
    assert "记录于 15 天前" in text and "可能过时" in text  # project 超阈值→警告
    assert "记录于 100 天前" in text
    assert "可能过时" not in text.split("跨专业考研")[1][:60]  # user 未超阈值→无警告


def test_render_memory_block_enforces_channel_budget_keeps_negatives() -> None:
    """D 预算在渲染侧执行（review I-3 回归）：精选超预算丢末位留痕，负面永远保留。

    原缺陷：CHANNEL_D_BUDGET 死常量，负面无上限可撑爆 D 通道 → assemble 第一刀把
    黑名单连同精选整体丢弃——安全信息恰在最需要时消失。
    """
    negatives = [_record(content="别推考研班", type_="feedback", valence="negative")]
    selected = [_record(content=f"画像{'x' * 400}{i}", type_="user") for i in range(4)]
    text, truncations = render_memory_block(
        selected=selected, negatives=negatives, select_max=3, budget=1000
    )
    assert "别推考研班" in text  # 负面安全信息在精选被裁时仍全量在场
    assert len(text) <= 1000 + 1  # D 通道预算被执行（+1 容忍负面独占时边界拼接差一）
    assert any("超 D 通道预算丢末位" in t.reason for t in truncations)


def test_render_memory_block_negatives_over_budget_survive_with_trace() -> None:
    """负面自身击穿预算：保留全部负面（安全优先不裁剪）+ 留痕供观测（review I-3 语义）。"""
    negatives = [_record(content="别" * 600, type_="feedback", valence="negative")]
    text, truncations = render_memory_block(
        selected=(), negatives=negatives, select_max=3, budget=100
    )
    assert "别" * 600 in text  # 不裁剪
    assert any(t.reason == "负面超 D 通道预算（安全优先不裁剪）" for t in truncations)
