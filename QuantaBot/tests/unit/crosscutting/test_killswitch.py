"""控制面（G5）行为测试：键解析、快照默认值、轮询刷新。"""

import asyncio

from quanta_bot.crosscutting.killswitch import (
    SWITCH_GRAYLIST_KEY,
    SWITCH_KILL_KEY,
    SWITCH_PERSONA_VERSION_KEY,
    ControlPlane,
    parse_snapshot,
)
from quanta_bot.infra.kv import InMemoryKV


def test_parse_snapshot_defaults() -> None:
    """键全缺 → 不杀、空灰名单、空人格版本（缺省=安全不杀，兜底靠 MQ 暂停）。"""
    snap = parse_snapshot({})
    assert snap.kill is False
    assert snap.graylist == ()
    assert snap.persona_version == ""


def test_parse_snapshot_kill_true_and_false() -> None:
    """kill 键：'true'（大小写/空白宽容）→ True；'false'/其他 → False。"""
    assert parse_snapshot({SWITCH_KILL_KEY: "true"}).kill is True
    assert parse_snapshot({SWITCH_KILL_KEY: " TRUE "}).kill is True
    assert parse_snapshot({SWITCH_KILL_KEY: "false"}).kill is False
    assert parse_snapshot({SWITCH_KILL_KEY: "1"}).kill is False  # 只认 'true'，不臆测其他写法


def test_parse_snapshot_graylist_and_persona() -> None:
    """灰名单为 JSON 整数数组字符串；脏数据宽容降级为空（轮询不应被运营误配置打断）。"""
    raw = {
        SWITCH_GRAYLIST_KEY: "[1, 2, 33]",
        SWITCH_PERSONA_VERSION_KEY: "v1.2",
    }
    snap = parse_snapshot(raw)
    assert snap.graylist == (1, 2, 33)
    assert snap.persona_version == "v1.2"
    bad = parse_snapshot({SWITCH_GRAYLIST_KEY: "not-json"})
    assert bad.graylist == ()


async def test_control_plane_refresh_and_snapshot() -> None:
    """refresh 读三键更新快照。"""
    kv = InMemoryKV()
    await kv.set(SWITCH_KILL_KEY, "true", ttl_seconds=60)
    cp = ControlPlane(kv, poll_seconds=5)
    assert cp.snapshot.kill is False  # 初始默认
    await cp.refresh()
    assert cp.snapshot.kill is True


async def test_control_plane_polling_updates_within_interval() -> None:
    """轮询任务在 poll_seconds 内吸收键变化（G5 ≤5s 生效口径）。"""
    kv = InMemoryKV()
    cp = ControlPlane(kv, poll_seconds=0)  # 测试用 0 间隔
    cp.start_polling()
    await asyncio.sleep(0.05)
    await kv.set(SWITCH_KILL_KEY, "true", ttl_seconds=60)
    await asyncio.sleep(0.1)
    assert cp.snapshot.kill is True
    cp.stop_polling()
