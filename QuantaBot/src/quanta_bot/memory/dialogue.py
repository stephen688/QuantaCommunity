"""memory/dialogue —— 对话级记忆（同帖多轮 @ 的 bot 发言链，Redis 楼层链 TTL）。

职责：写库成功后 append 本轮回复；下次同帖触发 read_chain 注入 B 通道 AI 历史区（Task 9 接线）。
定位：短期状态存储，不是向量（蓝图 §2.2）——TTL 过期即焚；
      C-2③ bot_history 是权威数据源，本链在 fake 模式/主服务不可用时兜底，合并注入时按内容去重、C-2③ 优先。
边界：read-modify-write 非原子——同帖串行（M2 单消费者）保证安全；跨帖天然隔离（键含 post_id）。
"""

import json
from datetime import datetime  # UTC 未导入——本模块不自造时间戳（created_at 由调用方传入）

from pydantic import BaseModel

from quanta_bot.crosscutting.ports import KeyValueStore


class DialogueTurn(BaseModel):
    """一轮对话（bot 视角：由哪条触发产生、回了什么）。"""

    turn_id: int  # 触发 comment_id（bot 回复的真实 comment_id 写库后才有，[联调校准点] C-5 返回值）
    reply_content: str
    created_at: datetime


def dialogue_key(post_id: int) -> str:
    return f"quantabot:dialogue:{post_id}"


async def append_turn(kv: KeyValueStore, post_id: int, turn: DialogueTurn, ttl_hours: int) -> None:
    turns = list(await read_chain(kv, post_id))  # 读改写（同帖串行下安全）
    turns.append(turn)
    await kv.set(
        dialogue_key(post_id),
        json.dumps([t.model_dump(mode="json") for t in turns]),
        ttl_hours * 3600,
    )


async def read_chain(kv: KeyValueStore, post_id: int) -> tuple[DialogueTurn, ...]:
    raw = await kv.get(dialogue_key(post_id))
    if raw is None:
        return ()
    return tuple(DialogueTurn.model_validate(item) for item in json.loads(raw))
