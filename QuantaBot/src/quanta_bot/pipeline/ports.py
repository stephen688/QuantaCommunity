"""pipeline/ports —— pipeline 消费的端口（协议）定义。

职责：ReplyWriter（写库）端口；M2 增 CommentTreeFetcher（评论树/帖子详情）。
边界：crosscutting 消费的端口在 crosscutting/ports.py（分层归属，勿混——AGENTS.md §4.1）；
      本文件零实现，infra 提供实现、composition 注入。
"""

from typing import Protocol

from pydantic import BaseModel

from quanta_bot.pipeline.generation import GeneratedReply


class LLMResult(BaseModel):
    """LLM 调用结果（usage 供成本折算与 trace 上报）。"""

    content: str
    prompt_tokens: int = 0
    completion_tokens: int = 0


class LLMClientError(Exception):
    """LLM 调用失败（网络/HTTP/响应契约不符统一包装；管线 failed 分支捕获类型）。"""


class LLMClient(Protocol):
    """LLM 端口（infra 提供 DeepSeekClient/FakeLLM 实现，composition 注入）。"""

    async def complete(self, system: str, user: str) -> LLMResult:
        """按 system+user 双消息生成回复（OpenAI 兼容形态；M3 起由人格层组装 prompt）。"""
        ...


class PostContent(BaseModel):
    """[Phase 0 对齐点 P0-2] 帖子主楼——字段随主服务帖子详情接口契约对齐，未定不臆造。"""

    post_id: int
    author_user_id: int
    title: str = ""
    content: str


class CommentNode(BaseModel):
    """[Phase 0 对齐点 P0-2] 评论节点——父链与 AI 发言标记是防穿越/防串味的关键字段。"""

    comment_id: int
    parent_comment_id: int | None = None
    author_user_id: int
    content: str
    is_ai: bool = False
    created_at: str = ""  # 契约敲定后改 datetime


class PostThread(BaseModel):
    """[Phase 0 对齐点 P0-2] 帖子线程：主楼 + 评论树 + AI 历史发言标记。"""

    post: PostContent
    comments: tuple[CommentNode, ...] = ()


class CommentTreeFetcher(Protocol):
    """评论树/帖子详情端口（P0-2；不可降级依赖——真实现可用前不可上线，技术选型 §5.5①）。"""

    async def fetch(self, post_id: int) -> PostThread:
        """拉取帖子线程（主楼+评论树）。"""
        ...


class ReplyWriter(Protocol):
    """写库端口（红线 §0.5：真实现必须走主服务写库入口，禁止直连数据库）。"""

    async def write_reply(self, reply: GeneratedReply) -> None:
        """把生成回复落库（fake：内存记录；M2：主服务 HTTP 写库）。"""
        ...
