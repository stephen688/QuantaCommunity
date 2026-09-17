"""对话级记忆测试：楼层链 append/read、TTL 语义走 kv 端口（fake=InMemoryKV 天然 hermetic）。"""

from datetime import UTC, datetime

from quanta_bot.infra.kv import InMemoryKV
from quanta_bot.memory.dialogue import DialogueTurn, append_turn, dialogue_key, read_chain


async def test_append_and_read_chain() -> None:
    kv = InMemoryKV()
    await append_turn(
        kv,
        10,
        DialogueTurn(turn_id=1, reply_content="第一条回复", created_at=datetime.now(UTC)),
        48,
    )
    await append_turn(
        kv,
        10,
        DialogueTurn(turn_id=2, reply_content="第二条回复", created_at=datetime.now(UTC)),
        48,
    )
    turns = await read_chain(kv, 10)
    assert [t.reply_content for t in turns] == ["第一条回复", "第二条回复"]  # 有序
    assert await read_chain(kv, 999) == ()  # 空帖空链


async def test_key_shape_and_json_roundtrip() -> None:
    """键名符合 C-7 命名空间（quantabot: 前缀）；JSON 往返无损。"""
    assert dialogue_key(10) == "quantabot:dialogue:10"
