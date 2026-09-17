"""context 组装测试：M2 异步渲染（fake 评论树）+ M3 B 通道（父链分区/保真截断/预算常量）。"""

from quanta_bot.infra.main_service import FakeCommentTreeFetcher
from quanta_bot.pipeline import context
from quanta_bot.pipeline.context import build_context
from quanta_bot.pipeline.ports import CommentNode, PostSummary, PostThread
from quanta_bot.pipeline.trigger import TriggerEvent


def _event() -> TriggerEvent:
    return TriggerEvent(
        event_id="evt-11",
        comment_id=11,
        post_id=22,
        commenter_user_id=5,
        content="@QuantaBot hi",
        mentioned_bot=True,
    )


def _floor(
    comment_id: int, parent_id: int | None = None, content: str = "楼层内容", user_id: int = 7
) -> CommentNode:
    return CommentNode(commentId=comment_id, parentId=parent_id, userId=user_id, content=content)


async def test_build_context_renders_thread() -> None:
    """渲染主楼 + 评论（含父链与 AI 标记）——最小可读文本，M3 再做筛选与长度预算。"""
    text = await build_context(_event(), FakeCommentTreeFetcher())
    assert "主楼" in text and "占位" in text
    assert "评论#" in text


async def test_build_context_fake_thread_matches_post() -> None:
    """fake fetcher 返回与触发事件同帖线程（隔离锚点正确）。"""
    thread = await FakeCommentTreeFetcher().fetch_context(_event())
    assert thread.post.post_id == 22
    assert all(isinstance(c.is_ai, bool) for c in thread.chain)


def test_partition_by_parent_chain_not_recency() -> None:
    """分区依据是父链关系不是机械最近 N 楼：老楼在链上=近区，新楼不在链上=远区。"""
    floors = [
        _floor(1, None, "老一级楼1"),
        _floor(2, 1, "楼中楼挂在1"),
        _floor(3, None, "老一级楼2"),
        _floor(4, 3, "触发评论父链所在楼中楼"),
        _floor(7, 4, "对链节点的直接回复"),
        _floor(5, None, "最新平行楼"),
        _floor(6, None, "次新平行楼"),
    ]
    nearby, remote = context.partition_floors(
        floors, chain_comment_ids={3, 4}, trigger_parent_floor_id=3
    )
    nearby_ids = {n.comment_id for n in nearby}
    assert {3, 4} <= nearby_ids  # 祖先链所在楼层全部保留（链差一个字接不上话茬）
    assert 7 in nearby_ids  # 对链节点的直接回复也进近区（楼中楼挂靠完整——2026-09-16 修正点）
    assert (
        5 in nearby_ids and 6 in nearby_ids
    )  # 最新 2 个平行一级楼层进近区（NEAR_PARALLEL_FLOORS）
    assert 1 in {r.comment_id for r in remote}  # 老平行楼进远区（压摘要）


def test_build_channel_b_never_truncates_ancestor_chain() -> None:
    """祖先链一字不砍；平行楼层超预算从最老丢；主楼超限保头尾掐中间。"""
    long_ancestor = _floor(4, 3, "祖先" * 500)  # 1000 字祖先链（超 B 预算也要保）
    old_parallel = [_floor(i, None, f"平行{i}" * 50) for i in range(10, 30)]
    post = PostSummary(postId=1, userId=2, title="t", content="主楼" * 4000)  # 8000 字主楼
    thread = PostThread(post=post, chain=(long_ancestor,), bot_history=())
    text, truncations = context.build_channel_b(thread, nearby=tuple(old_parallel))
    assert "祖先" * 500 in text  # 祖先链零截断（红线）
    assert len(text) <= context.CHANNEL_B_BUDGET  # B 通道总量有上界
    assert any(t.what.startswith("平行楼层") for t in truncations)  # 丢了最老平行楼层并留痕
    assert any("主楼" in t.what for t in truncations)  # 主楼掐中间也留痕


def test_truncate_head_tail_keeps_both_ends() -> None:
    """掐中间后头尾原样、长度=预算（截断标记计入预算）；未超限原样返回。"""
    text = "头" + "中" * 1000 + "尾"
    truncated = context.truncate_head_tail(text, 100)
    assert truncated.startswith("头")  # 开头定话题——原样保留
    assert truncated.endswith("尾")  # 结尾常带结论——原样保留
    assert "（中间截断）" in truncated  # 掐掉的中间留可读标记
    assert len(truncated) == 100  # 截断后长度=预算（含标记）
    assert context.truncate_head_tail("短文本", 100) == "短文本"  # 未超限不动


async def test_fake_fetcher_injectable_data() -> None:
    """FakeCommentTreeFetcher 构造参数可注入：fetch_context/fetch_floors 返回注入数据。"""
    post = PostSummary(postId=1, userId=2, title="注入主楼", content="注入内容")
    chain = (CommentNode(commentId=11, parentId=None, userId=5, content="注入链"),)
    floors = (CommentNode(commentId=12, parentId=None, userId=6, content="注入楼层"),)
    bot_history = (CommentNode(commentId=13, parentId=None, userId=9001, content="注入历史"),)
    fetcher = FakeCommentTreeFetcher(post=post, chain=chain, floors=floors, bot_history=bot_history)
    thread = await fetcher.fetch_context(_event())
    assert thread.post == post  # 注入主楼原样返回（不走占位形态）
    assert thread.chain == chain
    assert thread.bot_history == bot_history
    assert await fetcher.fetch_floors(22) == floors  # 注入楼层原样返回
