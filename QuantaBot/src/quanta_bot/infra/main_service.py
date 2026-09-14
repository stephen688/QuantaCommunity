"""infra/main_service —— 主服务契约客户端（M1：写库 fake；M2：httpx 真客户端）。

职责：fake_mode 下提供 ReplyWriter 的内存假实现（记录调用供集成测试断言）。
边界：M2 起本文件承载写库/检索/评论树/审核四个真客户端（httpx+Pydantic 契约校验）；
      禁止直连数据库（红线 §0.5——真实现走主服务 HTTP 入口）。
"""

from quanta_bot.pipeline.generation import GeneratedReply


class FakeReplyWriter:
    """内存 fake（集成测试断言 written 列表 = 写库调用记录）。"""

    def __init__(self) -> None:
        self.written: list[GeneratedReply] = []

    async def write_reply(self, reply: GeneratedReply) -> None:
        self.written.append(reply)
