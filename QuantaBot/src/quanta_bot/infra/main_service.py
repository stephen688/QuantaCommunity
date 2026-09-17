"""infra/main_service —— 主服务契约客户端（M1：写库 fake；M2：httpx 真客户端；M3：C-2② tree 分页 + fake 参数化）。

职责：fake_mode 下提供 ReplyWriter 的内存假实现（记录调用供集成测试断言）、
      CommentTreeFetcher 的内存 fake 评论树（可注入数据——eval runner 消费；缺省占位线程）。
边界：M2 起本文件承载写库/检索/评论树/审核四个真客户端（httpx+Pydantic 契约校验）；
      禁止直连数据库（红线 §0.5——真实现走主服务 HTTP 入口）。
"""

import httpx
from pydantic import BaseModel, ConfigDict, Field

from quanta_bot.pipeline.generation import GeneratedReply
from quanta_bot.pipeline.ports import (
    CommentFetchError,
    CommentNode,
    PostSummary,
    PostThread,
    ReplyWriteError,
)
from quanta_bot.pipeline.trigger import TriggerEvent


class MainServiceError(Exception):
    """主服务调用失败（Result 业务码非成功；HTTP 层错误由 httpx 异常表达）。"""


class MainServiceClient:
    """demo0 同步接口基座（C-5 service token 鉴权；httpx 直调）。"""

    def __init__(
        self,
        base_url: str,
        token: str,
        timeout_seconds: float,
        transport: httpx.AsyncBaseTransport | None = None,
    ) -> None:
        self._http = httpx.AsyncClient(
            base_url=base_url,
            timeout=timeout_seconds,
            headers={"Authorization": f"Bearer {token}"},
            transport=transport,
        )

    async def get_json(self, path: str, params: dict[str, object] | None = None) -> object:
        """GET 并剥 demo0 Result 外壳（成功返回 data）。

        [联调校准点] 外壳与成功码形状按 demo0 现有接口惯例（code/msg/data）；
        D1-D7 落地联调时如实际不同，改此处一处即可（所有 /bot/* 接口共用本基座）。
        """
        resp = await self._http.get(path, params=params)
        resp.raise_for_status()
        return _unwrap_result(resp.json())

    async def post_json(self, path: str, payload: dict[str, object]) -> object:
        """POST 并剥壳（写库用，Task 16）。"""
        resp = await self._http.post(path, json=payload)
        resp.raise_for_status()
        return _unwrap_result(resp.json())

    async def aclose(self) -> None:
        await self._http.aclose()


def _unwrap_result(body: object) -> object:
    """剥 Result 外壳：code 非成功 → MainServiceError；无外壳结构 → 原样返回（宽容校准期）。"""
    if isinstance(body, dict) and "code" in body:
        if body.get("code") not in (200, 0, "200", "0"):
            raise MainServiceError(f"主服务业务失败：{body.get('msg')}")

        return body.get("data")
    return body


class HTTPCommentTreeFetcher:
    """C-2 真客户端：chain（触发评论+父链+主楼摘要）+ history（bot 本帖历史）+ tree（C-2② 全量楼层分页）。

    history 的 postId=None 全站模式留 M3 用户级记忆。
    """

    def __init__(self, client: MainServiceClient, bot_user_id: int) -> None:
        self._client = client
        self._bot_user_id = bot_user_id

    async def fetch_context(self, event: TriggerEvent) -> PostThread:
        try:
            # C-2① 评论+父级链+主楼摘要
            chain_data = await self._client.get_json(
                "/bot/comment/chain", params={"commentId": event.comment_id}
            )
            parsed = CommentChainResponse.model_validate(chain_data)
            # C-2③ bot 本帖历史发言（防穿越楼层快照数据源）
            history_data = await self._client.get_json(
                "/bot/comment/history",
                params={
                    "userId": self._bot_user_id,
                    "postId": event.post_id,
                    "pageNum": 1,
                    "pageSize": 50,
                },
            )
            history = CommentHistoryResponse.model_validate(history_data)
        except (httpx.HTTPError, MainServiceError) as exc:
            raise CommentFetchError(f"评论树拉取失败（chain/history）：{exc}") from exc
        return PostThread(
            post=parsed.post,
            chain=tuple(self._mark_ai(node) for node in parsed.chain),
            bot_history=tuple(self._mark_ai(node) for node in history.comments),
        )

    async def fetch_floors(self, post_id: int) -> tuple[CommentNode, ...]:
        """C-2② 全量楼层（分页循环拉满 total——近远区分区与远区摘要的数据源）。"""
        collected: list[CommentNode] = []
        page_num, page_size = 1, 50
        while True:
            try:
                # ① 逐页拉取（sortType=asc：楼层按时间正序，拼接后即天然有序）
                data = await self._client.get_json(
                    "/bot/comment/tree",
                    params={
                        "postId": post_id,
                        "pageNum": page_num,
                        "pageSize": page_size,
                        "sortType": "asc",
                    },
                )
                # ② get_json 已剥 Result 外壳，此处直接做 DTO 校验（与 fetch_context 同口径）
                parsed = CommentTreePage.model_validate(data)
            except (httpx.HTTPError, MainServiceError) as exc:
                raise CommentFetchError(f"楼层拉取失败（tree 第{page_num}页）：{exc}") from exc
            collected.extend(parsed.list)
            # ③ 取满 total 即止；空页防御——total 虚高时不死循环
            if len(collected) >= parsed.total or not parsed.list:
                return tuple(self._mark_ai(node) for node in collected)
            page_num += 1

    def _mark_ai(self, node: CommentNode) -> CommentNode:
        """按 user_id==bot 标记 AI 发言（客户端本地判定，不依赖服务端字段）。"""
        if node.user_id == self._bot_user_id and not node.is_ai:
            return node.model_copy(update={"is_ai": True})
        return node


class HTTPReplyWriter:
    """C-5/P0-5 真写库：POST /comment/send（CommentAddDTO 原样；红线 §0.5 单一入口）。

    机审语义（C-6）：提交成功≠最终可见——demo0 异步 AI 机审驳回则回复不可见（由 demo0 链路
    自动处理，bot 侧无同步感知；决策日志 replied 口径=「已提交写库」）。
    """

    def __init__(self, client: MainServiceClient) -> None:
        self._client = client

    async def write_reply(self, reply: GeneratedReply) -> None:
        payload: dict[str, object] = {
            "contentId": reply.post_id,
            "answerId": reply.answer_id,
            "parentId": reply.parent_floor_comment_id,
            "replyCommentId": reply.reply_to_comment_id,
            "replyUserId": reply.reply_to_user_id,
            "content": reply.content,
            "imageUrls": [],  # bot 纯文本回复（M3 多模态再扩）
        }
        try:
            await self._client.post_json("/comment/send", payload)
        except (httpx.HTTPError, MainServiceError) as exc:
            raise ReplyWriteError(f"主服务写库失败：{exc}") from exc


class CommentChainResponse(BaseModel):
    """[C-2① 联调校准点] chain 接口响应（demo0 D5 实施时对齐字段名）。"""

    model_config = ConfigDict(populate_by_name=True)

    post: PostSummary
    chain: tuple[CommentNode, ...] = ()


class CommentHistoryResponse(BaseModel):
    """[C-2③ 联调校准点] history 接口响应（分页 list + total）。"""

    model_config = ConfigDict(populate_by_name=True)

    comments: tuple[CommentNode, ...] = Field(default=(), alias="list")
    total: int = 0


class CommentTreePage(BaseModel):
    """[C-2② 联调校准点] tree 分页响应（demo0 D5 强类型 DTO 对齐字段名）。"""

    model_config = ConfigDict(populate_by_name=True)

    total: int = 0
    list: tuple[CommentNode, ...] = ()


class FakeReplyWriter:
    """内存 fake（集成测试断言 written 列表 = 写库调用记录）。"""

    def __init__(self) -> None:
        self.written: list[GeneratedReply] = []

    async def write_reply(self, reply: GeneratedReply) -> None:
        self.written.append(reply)


class UnimplementedReplyWriter:
    """真模式写库占位（main_service 未配置时；C-5 契约已对齐，真客户端=Task 16 HTTPReplyWriter）：
    调用即抛 ReplyWriteError，由管线记 failed。

    诚实口径：绝不静默假装写库成功（红线 §0.5 精神）。
    """

    async def write_reply(self, reply: GeneratedReply) -> None:
        raise ReplyWriteError("写库真客户端未接入（[Phase 0 对齐点 P0-5]，Tranche B 落地）")


class FakeCommentTreeFetcher:
    """内存 fake（可注入数据——集成测试/eval runner 用；缺省沿用 M2 固定形态）。"""

    def __init__(
        self,
        post: PostSummary | None = None,
        chain: tuple[CommentNode, ...] = (),
        floors: tuple[CommentNode, ...] = (),
        bot_history: tuple[CommentNode, ...] = (),
    ) -> None:
        self._post = post
        self._chain = chain
        self._floors = floors
        self._bot_history = bot_history

    async def fetch_context(self, event: TriggerEvent) -> PostThread:
        # 注入形态：post 非空即按注入数据原样返回（eval runner 消费）
        if self._post is not None:
            return PostThread(post=self._post, chain=self._chain, bot_history=self._bot_history)
        # 缺省沿用 M2 固定形态（既有测试与 composition fake 装配不破）
        post = PostSummary(
            post_id=event.post_id,
            author_user_id=1,
            title="占位主楼",
            content="（M2 fake 评论树：主楼内容占位）",
        )
        chain = (
            CommentNode(
                comment_id=event.comment_id,
                parent_id=event.parent_id,
                reply_comment_id=event.reply_comment_id,
                user_id=event.commenter_user_id,
                content=event.content,
            ),
        )
        return PostThread(post=post, chain=chain)

    async def fetch_floors(self, post_id: int) -> tuple[CommentNode, ...]:
        # 注入楼层原样返回（分区/摘要数据源；post_id 仅为对齐端口签名——fake 单帖数据不筛帖）
        return self._floors
