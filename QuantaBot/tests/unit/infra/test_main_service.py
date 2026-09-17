"""主服务客户端行为测试（httpx MockTransport 隔离网络——C-2/C-5 契约形状与响应解析）。"""

import json

import httpx
import pytest

from quanta_bot.infra.main_service import (
    HTTPCommentTreeFetcher,
    HTTPReplyWriter,
    MainServiceClient,
    MainServiceError,
)
from quanta_bot.pipeline.generation import GeneratedReply
from quanta_bot.pipeline.trigger import TriggerEvent

_CHAIN_BODY = {
    "code": 200,
    "msg": "success",
    "data": {
        "post": {"postId": 9, "userId": 1, "title": "主楼标题", "content": "主楼内容"},
        "chain": [
            {
                "commentId": 100,
                "parentId": None,
                "replyCommentId": None,
                "userId": 2,
                "content": "一级评论",
                "createTime": "2026-09-15 09:00:00",
            },
            {
                "commentId": 101,
                "parentId": 100,
                "replyCommentId": 100,
                "userId": 5,
                "content": "@QuantaBot hi",
                "createTime": "2026-09-15 10:00:00",
            },
        ],
    },
}

_HISTORY_BODY = {
    "code": 200,
    "msg": "success",
    "data": {
        "list": [
            {
                "commentId": 90,
                "parentId": 1,
                "replyCommentId": 1,
                "userId": 9001,
                "content": "AI 之前的回复",
                "createTime": "2026-09-15 09:30:00",
            },
        ],
        "total": 1,
    },
}


def _client(handler) -> MainServiceClient:
    transport = httpx.MockTransport(handler)
    return MainServiceClient("http://demo0.test", "svc-token", 10.0, transport=transport)


def _event() -> TriggerEvent:
    return TriggerEvent.model_validate(
        {
            "eventId": "evt-1",
            "commentId": 101,
            "postId": 9,
            "commenterUserId": 5,
            "commentContent": "@QuantaBot hi",
            "mentionedBot": True,
            "botTriggerKind": "mentioned",
        }
    )


async def test_http_comment_tree_fetch_context_contract() -> None:
    """C-2 契约：chain+history 请求形状（路径/参数/鉴权头）+ 响应解析 + is_ai 本地标记。"""
    seen: list[httpx.Request] = []

    def handler(request: httpx.Request) -> httpx.Response:
        seen.append(request)
        if request.url.path == "/bot/comment/chain":
            return httpx.Response(200, json=_CHAIN_BODY)
        assert request.url.path == "/bot/comment/history"
        return httpx.Response(200, json=_HISTORY_BODY)

    client = _client(handler)
    fetcher = HTTPCommentTreeFetcher(client, bot_user_id=9001)
    try:
        thread = await fetcher.fetch_context(_event())
    finally:
        await client.aclose()
    # 解析
    assert thread.post.post_id == 9
    assert thread.post.title == "主楼标题"
    assert [n.comment_id for n in thread.chain] == [100, 101]
    assert thread.chain[1].parent_id == 100
    assert thread.bot_history[0].comment_id == 90
    assert thread.bot_history[0].is_ai is True  # userId==bot → 客户端本地标记
    assert all(not n.is_ai for n in thread.chain)
    # 请求形状（C-2① / C-2③）
    chain_req, history_req = seen[0], seen[1]
    assert chain_req.url.params["commentId"] == "101"
    assert chain_req.headers["Authorization"] == "Bearer svc-token"
    assert history_req.url.params["userId"] == "9001"
    assert history_req.url.params["postId"] == "9"
    assert history_req.url.params["pageNum"] == "1"
    assert history_req.url.params["pageSize"] == "50"


async def test_fetch_floors_paginates_until_total() -> None:
    """C-2② tree 分页：total=120 → 3 次请求（pageNum=1/2/3、pageSize=50），按序拼接 120 条。"""
    seen: list[httpx.Request] = []

    def handler(request: httpx.Request) -> httpx.Response:
        seen.append(request)
        page_num = int(request.url.params["pageNum"])
        start = (page_num - 1) * 50
        end = min(start + 50, 120)  # 末页只回剩余条数（真实分页语义）
        page_list = [
            {
                "commentId": comment_id,
                "parentId": None,
                "replyCommentId": None,
                "userId": 2,
                "content": f"楼层{comment_id}",
                "createTime": "2026-09-15 09:00:00",
            }
            for comment_id in range(start + 1, end + 1)
        ]
        return httpx.Response(
            200, json={"code": 200, "msg": "success", "data": {"total": 120, "list": page_list}}
        )

    client = _client(handler)
    fetcher = HTTPCommentTreeFetcher(client, bot_user_id=9001)
    try:
        floors = await fetcher.fetch_floors(9)
    finally:
        await client.aclose()
    assert [n.comment_id for n in floors] == list(range(1, 121))  # 按序拼接全量楼层
    assert [r.url.params["pageNum"] for r in seen] == ["1", "2", "3"]  # 取满 total 即止（3 页）
    assert all(r.url.path == "/bot/comment/tree" for r in seen)  # C-2② tree 端点
    assert all(r.url.params["pageSize"] == "50" for r in seen)
    assert all(r.url.params["postId"] == "9" for r in seen)


async def test_main_service_business_failure_raises() -> None:
    """业务码失败（Result code 非成功）→ MainServiceError。"""

    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(200, json={"code": 500, "msg": "内部错误", "data": None})

    client = _client(handler)
    with pytest.raises(MainServiceError):
        await client.get_json("/bot/comment/chain", params={"commentId": 101})


async def test_main_service_http_error_propagates() -> None:
    """HTTP 错误（5xx）→ httpx.HTTPError 上抛（由消费兜底记 failed，不在客户端吞）。"""
    transport = httpx.MockTransport(lambda request: httpx.Response(503))
    client = MainServiceClient("http://demo0.test", "svc-token", 10.0, transport=transport)
    with pytest.raises(httpx.HTTPError):
        await client.get_json("/bot/comment/chain", params={"commentId": 1})


_REPLY = GeneratedReply(
    post_id=9,
    answer_id=None,
    reply_to_comment_id=101,
    reply_to_user_id=5,
    parent_floor_comment_id=100,
    content="[QuantaBot·AI 学长] 回复内容",
)


async def test_http_reply_writer_posts_comment_add_dto() -> None:
    """C-5/P0-5 契约：POST /comment/send 请求体=CommentAddDTO 映射（含鉴权头）。"""
    seen: list[httpx.Request] = []

    def handler(request: httpx.Request) -> httpx.Response:
        seen.append(request)
        return httpx.Response(200, json={"code": 200, "msg": "success", "data": None})

    client = _client(handler)
    writer = HTTPReplyWriter(client)
    try:
        await writer.write_reply(_REPLY)
    finally:
        await client.aclose()
    assert len(seen) == 1
    req = seen[0]
    assert req.url.path == "/comment/send"
    assert req.headers["Authorization"] == "Bearer svc-token"
    body = json.loads(req.content)
    assert body["contentId"] == 9
    assert body["answerId"] is None
    assert body["parentId"] == 100
    assert body["replyCommentId"] == 101
    assert body["replyUserId"] == 5
    assert body["content"] == "[QuantaBot·AI 学长] 回复内容"
    assert body["imageUrls"] == []


async def test_http_reply_writer_wraps_failure() -> None:
    """写库失败（业务码/HTTP）→ ReplyWriteError（管线 failed 分支捕获类型）。"""
    from quanta_bot.pipeline.ports import ReplyWriteError

    transport = httpx.MockTransport(
        lambda request: httpx.Response(200, json={"code": 500, "msg": "被限流"})
    )
    client = MainServiceClient("http://demo0.test", "svc-token", 10.0, transport=transport)
    writer = HTTPReplyWriter(client)
    with pytest.raises(ReplyWriteError):
        await writer.write_reply(_REPLY)
