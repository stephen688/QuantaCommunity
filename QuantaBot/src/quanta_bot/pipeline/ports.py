"""pipeline/ports —— pipeline 消费的端口（协议）定义。

职责：ReplyWriter（写库）端口；M2 增 CommentTreeFetcher（评论树/帖子详情）。
边界：crosscutting 消费的端口在 crosscutting/ports.py（分层归属，勿混——AGENTS.md §4.1）；
      本文件零实现，infra 提供实现、composition 注入。
"""

from typing import Protocol

from quanta_bot.pipeline.generation import GeneratedReply


class ReplyWriter(Protocol):
    """写库端口（红线 §0.5：真实现必须走主服务写库入口，禁止直连数据库）。"""

    async def write_reply(self, reply: GeneratedReply) -> None:
        """把生成回复落库（fake：内存记录；M2：主服务 HTTP 写库）。"""
        ...
