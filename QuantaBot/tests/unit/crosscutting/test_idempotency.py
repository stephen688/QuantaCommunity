"""幂等去重（comment_id 键）的行为测试。"""

from quanta_bot.crosscutting.idempotency import check_and_mark
from quanta_bot.crosscutting.ports import KeyValueStore
from quanta_bot.infra.kv import InMemoryKV


async def test_first_comment_id_passes_duplicate_blocked() -> None:
    """首次 comment_id 通过；重复投递被拦（MQ 至少一次投递语义）。"""
    kv: KeyValueStore = InMemoryKV()
    assert await check_and_mark(kv, comment_id=101) is True
    assert await check_and_mark(kv, comment_id=101) is False
    assert await check_and_mark(kv, comment_id=102) is True
