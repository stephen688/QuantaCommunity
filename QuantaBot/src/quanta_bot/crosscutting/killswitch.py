"""crosscutting/killswitch —— 控制面配置键轮询（G5：Redis 键广播，Agent 侧 ≤5s 感知）。

职责：kill/灰度白名单/人格版本三键的读取、解析、快照与后台轮询任务管理。
边界：只做感知与快照，不做执行——回复短路在 pipeline（Task 9），消费暂停在 consumer（Tranche B），
      全量演练在 M5；轮询失败保留旧快照（Redis 抖动不打断业务，下轮重试）。
键口径（P0-7）：quantabot:switch:kill='true'/'false'；
      graylist=JSON 整数数组字符串；persona_version=任意字符串。
"""

import asyncio
import json
import logging

from pydantic import BaseModel

from quanta_bot.crosscutting.ports import KeyValueStore

logger = logging.getLogger(__name__)

SWITCH_KILL_KEY = "quantabot:switch:kill"
SWITCH_GRAYLIST_KEY = "quantabot:switch:graylist"
SWITCH_PERSONA_VERSION_KEY = "quantabot:switch:persona_version"

_ALL_SWITCH_KEYS = (SWITCH_KILL_KEY, SWITCH_GRAYLIST_KEY, SWITCH_PERSONA_VERSION_KEY)


class ControlPlaneSnapshot(BaseModel):
    """控制面快照（轮询周期的最新一致视图）。"""

    kill: bool = False
    graylist: tuple[int, ...] = ()
    persona_version: str = ""


def parse_snapshot(raw: dict[str, str | None]) -> ControlPlaneSnapshot:
    """把三键原始值解析为快照（脏数据宽容降级，不打断轮询）。"""
    kill = (raw.get(SWITCH_KILL_KEY) or "").strip().lower() == "true"
    graylist: tuple[int, ...] = ()
    gray_raw = (raw.get(SWITCH_GRAYLIST_KEY) or "").strip()
    if gray_raw:
        try:
            graylist = tuple(int(x) for x in json.loads(gray_raw))
        except (ValueError, TypeError):
            logger.warning("灰名单键值非 JSON 整数数组，降级为空：%r", gray_raw)
    persona_version = (raw.get(SWITCH_PERSONA_VERSION_KEY) or "").strip()
    return ControlPlaneSnapshot(kill=kill, graylist=graylist, persona_version=persona_version)


class ControlPlane:
    """控制面轮询客户端（kv 端口注入；composition 装配，server lifespan 托管启停）。"""

    def __init__(self, kv: KeyValueStore, poll_seconds: int = 5) -> None:
        self._kv = kv
        self._poll_seconds = max(poll_seconds, 0)
        self._snapshot = ControlPlaneSnapshot()
        self._task: asyncio.Task[None] | None = None

    @property
    def snapshot(self) -> ControlPlaneSnapshot:
        """最新快照（默认值=不杀/无灰名单——键缺失即安全态，兜底靠 MQ 消费暂停）。"""
        return self._snapshot

    async def refresh(self) -> None:
        """读三键并更新快照（也可手动调用，测试/运维即时生效用）。"""
        raw: dict[str, str | None] = {key: await self._kv.get(key) for key in _ALL_SWITCH_KEYS}
        self._snapshot = parse_snapshot(raw)

    async def run_forever(self) -> None:
        """轮询主循环（server lifespan 启动的后台任务）。"""
        while True:
            try:
                await self.refresh()
            except Exception as exc:  # 轮询边界：失败保留旧快照，下轮重试（不打断业务）
                logger.warning("控制面轮询失败（保留旧快照）：%s", exc)
            await asyncio.sleep(self._poll_seconds)

    def start_polling(self) -> None:
        """启动后台轮询任务（幂等：已在跑则不动）。"""
        if self._task is None or self._task.done():
            self._task = asyncio.create_task(self.run_forever())

    def stop_polling(self) -> None:
        """停止轮询任务（server shutdown 调用）。"""
        if self._task is not None:
            self._task.cancel()
            self._task = None
