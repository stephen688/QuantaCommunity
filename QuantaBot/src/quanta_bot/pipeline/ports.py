"""pipeline/ports —— pipeline 消费的端口（协议）定义。

职责：ReplyWriter（写库）端口；M2 增 CommentTreeFetcher（评论树/帖子详情）。
边界：crosscutting 消费的端口在 crosscutting/ports.py（分层归属，勿混——AGENTS.md §4.1）；
      本文件零实现，infra 提供实现、composition 注入。
"""

from typing import Protocol

from pydantic import BaseModel, ConfigDict, Field

from quanta_bot.crosscutting.ports import Decision
from quanta_bot.pipeline.generation import GeneratedReply
from quanta_bot.pipeline.trigger import TriggerEvent


class LLMResult(BaseModel):
    """LLM 调用结果（usage 供成本折算与 trace 上报）。"""

    content: str
    prompt_tokens: int = 0
    completion_tokens: int = 0


class LLMClientError(Exception):
    """LLM 调用失败（网络/HTTP/响应契约不符统一包装；管线 failed 分支捕获类型）。"""


class LLMClient(Protocol):
    """LLM 端口（infra 提供 DeepSeekClient/FakeLLM 实现，composition 注入）。

    json_mode=True 请求 JSON 结构化输出（决策层/摘要等轻量调用）；
    max_tokens 限制输出上限（轻量调用的成本闸）。
    """

    async def complete(
        self, system: str, user: str, *, json_mode: bool = False, max_tokens: int | None = None
    ) -> LLMResult:
        """按 system+user 双消息生成回复（OpenAI 兼容形态；M3 起由人格层组装 prompt）。"""
        ...


class PostSummary(BaseModel):
    """[C-2① 联调校准点] 帖子主楼摘要（响应外壳由 MainServiceClient 剥壳）。"""

    model_config = ConfigDict(populate_by_name=True)

    post_id: int = Field(alias="postId")
    author_user_id: int = Field(alias="userId")
    title: str = ""
    content: str


class CommentNode(BaseModel):
    """[C-2① 联调校准点] 评论节点（chain/history 共用；is_ai 为客户端本地标记）。"""

    model_config = ConfigDict(populate_by_name=True)

    comment_id: int = Field(alias="commentId")
    parent_id: int | None = Field(default=None, alias="parentId")
    reply_comment_id: int | None = Field(default=None, alias="replyCommentId")
    user_id: int = Field(alias="userId")
    content: str
    images: tuple[str, ...] = Field(default=(), alias="images")
    create_time: str = Field(default="", alias="createTime")
    is_ai: bool = Field(default=False, description="客户端按 user_id==bot_user_id 本地标记")


class PostThread(BaseModel):
    """帖子线程：主楼 + 触发评论父链（C-2①）+ bot 本帖历史（C-2③，防穿越快照数据源）。"""

    post: PostSummary
    chain: tuple[CommentNode, ...] = ()
    bot_history: tuple[CommentNode, ...] = ()


class CommentTreeFetcher(Protocol):
    """评论树/帖子详情端口（C-2；不可降级依赖——真实现可用前不可上线，技术选型 §5.5①）。"""

    async def fetch_context(self, event: TriggerEvent) -> PostThread:
        """拉取组装上下文所需线程（主楼+触发评论父链+bot 本帖历史）。"""
        ...

    async def fetch_floors(self, post_id: int) -> tuple[CommentNode, ...]:
        """C-2② 全量楼层（分页拉满 total——近远区分区与远区摘要的数据源）。"""
        ...


class RunTrace(BaseModel):
    """一次管线 run 的观测轨迹（SQLite 决策明细的观测侧伴生——Langfuse trace 载体）。

    契约：字段与 DecisionLogEntry 决策口径一致（诚实统计），额外带生成细节与成本。
    """

    comment_id: int
    post_id: int
    trigger_content: str
    decision: Decision
    mode: str | None = None
    reason: str
    context_text: str | None = None
    generated_content: str | None = None
    prompt_tokens: int | None = None
    completion_tokens: int | None = None
    cost_li: int | None = None
    daily_cost_li_after: int | None = None
    error: str | None = None


class RunTracer(Protocol):
    """run 轨迹上报端口（实现方必须内部兜底——观测故障不得影响业务，允许 WARNING 留痕）。"""

    async def record(self, trace: RunTrace) -> None:
        """上报一条 run 轨迹（不抛异常是本端口的硬契约）。"""
        ...


class ReplyWriter(Protocol):
    """写库端口（红线 §0.5：真实现必须走主服务写库入口，禁止直连数据库）。"""

    async def write_reply(self, reply: GeneratedReply) -> None:
        """把生成回复落库（fake：内存记录；M2：主服务 HTTP 写库）。"""
        ...


class ReplyWriteError(Exception):
    """写库端口失败（真客户端 HTTP 错误 / P0-5 未接入占位抛出；管线 failed 分支捕获类型）。"""
