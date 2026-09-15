"""infra/main_service —— 主服务契约客户端（M1：写库 fake；M2：httpx 真客户端）。

职责：fake_mode 下提供 ReplyWriter 的内存假实现（记录调用供集成测试断言）、
      CommentTreeFetcher 的内存 fake 评论树（返回占位线程，供 context 组装与装配测试）。
边界：M2 起本文件承载写库/检索/评论树/审核四个真客户端（httpx+Pydantic 契约校验）；
      禁止直连数据库（红线 §0.5——真实现走主服务 HTTP 入口）。
"""

from quanta_bot.pipeline.generation import GeneratedReply
from quanta_bot.pipeline.ports import CommentNode, PostContent, PostThread


class FakeReplyWriter:
    """内存 fake（集成测试断言 written 列表 = 写库调用记录）。"""

    def __init__(self) -> None:
        self.written: list[GeneratedReply] = []

    async def write_reply(self, reply: GeneratedReply) -> None:
        self.written.append(reply)


class FakeCommentTreeFetcher:
    """内存 fake 评论树（返回与 post_id 同帖的最小占位线程——真客户端替换点=HTTPCommentTreeFetcher，Task 15）。"""

    async def fetch(self, post_id: int) -> PostThread:
        post = PostContent(
            post_id=post_id,
            author_user_id=1,
            title="占位主楼",
            content="（M2 fake 评论树：主楼内容占位）",
        )
        comments = (
            CommentNode(
                comment_id=1, parent_comment_id=None, author_user_id=2, content="占位评论1"
            ),
            CommentNode(
                comment_id=2, parent_comment_id=1, author_user_id=3, content="占位评论2（回复1楼）"
            ),
        )
        return PostThread(post=post, comments=comments)
