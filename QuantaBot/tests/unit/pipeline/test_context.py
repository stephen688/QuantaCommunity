"""context 组装测试：M3 B 通道（父链分区/保真截断）+ C 摘要与四通道总装（build_context 旧路径已随 Task 9 删除）。"""

import pytest

from quanta_bot.infra.deepseek import FakeLLM
from quanta_bot.infra.kv import InMemoryKV
from quanta_bot.infra.main_service import FakeCommentTreeFetcher
from quanta_bot.pipeline import context
from quanta_bot.pipeline.context import (
    TOTAL_CONTEXT_BUDGET,
    LLMSummarizer,
    SummaryError,
    assemble,
    build_channel_c,
)
from quanta_bot.pipeline.ports import CommentNode, LLMClientError, PostSummary, PostThread
from quanta_bot.pipeline.trigger import TriggerEvent

_SUMMARY_FIVE_KEYS = '{"topic": "选课", "conclusions": [], "disputes": [], "unanswered_questions": [], "key_facts": []}'


def _event() -> TriggerEvent:
    return TriggerEvent(
        event_id="evt-11",
        comment_id=11,
        post_id=22,
        commenter_user_id=5,
        content="@框框 hi",
        mentioned_bot=True,
    )


def _floor(
    comment_id: int,
    parent_id: int | None = None,
    content: str = "楼层内容",
    user_id: int = 7,
    create_time: str = "",
) -> CommentNode:
    return CommentNode(
        commentId=comment_id,
        parentId=parent_id,
        userId=user_id,
        content=content,
        createTime=create_time,
    )


def test_waterline_falls_back_to_comment_id_when_time_is_missing() -> None:
    """缺少 createTime 时按触发 comment_id 收口，未知后发楼层宁可丢弃。"""
    event = TriggerEvent(
        event_id="evt-100",
        comment_id=100,
        post_id=22,
        commenter_user_id=5,
        content="@框框 水位测试",
        mentioned_bot=True,
    )
    trigger_node = _floor(100, content="@框框 水位测试")
    thread = PostThread(
        post=PostSummary(postId=22, userId=1, title="水位", content="讨论"),
        chain=(trigger_node,),
    )
    floors = (
        _floor(99, content="触发前"),
        _floor(100, content="触发评论"),
        _floor(101, content="触发后"),
    )

    filtered = context.filter_floors_at_waterline(event, thread, floors)

    assert [node.comment_id for node in filtered] == [99, 100]


def test_waterline_uses_timestamp_before_comment_id_when_both_are_known() -> None:
    """时间齐全时以真实先后为准；ID 只用于缺时间兼容，不能误删旧楼或放入新楼。"""
    event = TriggerEvent(
        event_id="evt-100",
        comment_id=100,
        post_id=22,
        commenter_user_id=5,
        content="@框框 水位测试",
        mentioned_bot=True,
    )
    trigger_node = CommentNode(
        commentId=100,
        parentId=None,
        userId=5,
        content="@框框 水位测试",
        createTime="2026-09-19T10:00:00+00:00",
    )
    thread = PostThread(
        post=PostSummary(postId=22, userId=1, title="水位", content="讨论"),
        chain=(trigger_node,),
    )
    older_with_larger_id = CommentNode(
        commentId=101,
        parentId=None,
        userId=7,
        content="实际更早",
        createTime="2026-09-19T09:59:00+00:00",
    )
    newer_with_smaller_id = CommentNode(
        commentId=99,
        parentId=None,
        userId=7,
        content="实际更晚",
        createTime="2026-09-19T10:01:00+00:00",
    )

    filtered = context.filter_floors_at_waterline(
        event, thread, (older_with_larger_id, newer_with_smaller_id)
    )

    assert [node.comment_id for node in filtered] == [101]


def test_waterline_same_second_later_floor_excluded_by_comment_id() -> None:
    """同秒并发：时间同秒时以 comment_id 决胜——后发楼层（id 更大）不得穿越进上下文。

    2026-09-24 轨道 B 真实链路复现：触发 404 与楼层 405 同秒落库，405 进近区上下文，
    回复 406 复述了仅存在于 405 的词面（与 S14 机审污染同源）。DB create_time 只有
    秒级精度，同秒内 comment_id 自增序=插入序，是唯一可靠的决胜依据。
    """
    event = TriggerEvent(
        event_id="evt-404",
        comment_id=404,
        post_id=22,
        commenter_user_id=5,
        content="@框框 水位测试",
        mentioned_bot=True,
    )
    trigger_node = _floor(
        404, content="@框框 水位测试", user_id=5, create_time="2026-09-24T22:05:41+00:00"
    )
    thread = PostThread(
        post=PostSummary(postId=22, userId=1, title="水位", content="讨论"),
        chain=(trigger_node,),
    )
    floors = (
        _floor(402, content="早一秒", create_time="2026-09-24T22:05:40+00:00"),
        _floor(403, content="同秒先发", create_time="2026-09-24T22:05:41+00:00"),
        trigger_node,  # 触发评论自身：同秒同 id，必须保留
        _floor(405, content="同秒后发", create_time="2026-09-24T22:05:41+00:00"),
    )

    filtered = context.filter_floors_at_waterline(event, thread, floors)

    assert [node.comment_id for node in filtered] == [402, 403, 404]


def test_waterline_same_second_missing_time_falls_back_to_id_gate() -> None:
    """同秒并发且后发楼层缺 createTime：回退 comment_id 门，id 大于触发的楼层宁可丢弃。"""
    event = TriggerEvent(
        event_id="evt-404",
        comment_id=404,
        post_id=22,
        commenter_user_id=5,
        content="@框框 水位测试",
        mentioned_bot=True,
    )
    trigger_node = _floor(
        404, content="@框框 水位测试", user_id=5, create_time="2026-09-24T22:05:41+00:00"
    )
    thread = PostThread(
        post=PostSummary(postId=22, userId=1, title="水位", content="讨论"),
        chain=(trigger_node,),
    )
    floors = (
        _floor(403, content="同秒先发", create_time="2026-09-24T22:05:41+00:00"),
        _floor(405, content="同秒后发但缺时间"),  # create_time 缺失（空串→不可解析）
    )

    filtered = context.filter_floors_at_waterline(event, thread, floors)

    assert [node.comment_id for node in filtered] == [403]


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


def test_build_channel_b_labels_comment_author_user_id() -> None:
    """评论作者 ID 显式标注为用户，避免被模型误解为楼层号。"""
    thread = PostThread(
        post=PostSummary(postId=1, userId=2, title="t", content="主楼"),
        chain=(_floor(4, 3, "祖先评论", user_id=17),),
    )
    text, _ = context.build_channel_b(thread, nearby=())
    assert "用户#17：" in text
    assert "】17：" not in text


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


async def test_summarizer_non_dict_retry_raises_summary_error() -> None:
    """retry 返回非 dict JSON（list）：SummaryError 降级信号而非 AttributeError 逃逸。

    reviewer 实证缺陷：逃逸会击穿 build_channel_c 的 except SummaryError 降级与管线
    failed 分支，落进 consumer 兜底 → 整条回复静默丢弃，违背「摘要失败=丢远区照常回复」契约。
    """
    llm = FakeLLM(responses=["坏JSON", '["topic"]'])
    with pytest.raises(SummaryError):
        await LLMSummarizer(llm).summarize((_floor(1),))


async def test_summarizer_retry_missing_fields_raises_summary_error() -> None:
    """retry 返回 dict 但缺字段：与首次同样严格校验（原 retry 路径不校验五字段——reviewer 实证）。"""
    llm = FakeLLM(responses=["坏JSON", '{"topic": "选课"}'])
    with pytest.raises(SummaryError):
        await LLMSummarizer(llm).summarize((_floor(1),))


async def test_summarizer_first_non_dict_retries_and_succeeds() -> None:
    """首次返回 list（合法 JSON 非 object）：形状校验进自愈环——喂回重试成功后正常渲染。"""
    llm = FakeLLM(responses=['["topic"]', _SUMMARY_FIVE_KEYS])
    text = await LLMSummarizer(llm).summarize((_floor(1),))
    assert "选课" in text


def test_summary_cache_key_covers_all_floor_ids() -> None:
    """缓存 key 覆盖全部楼层 id（review I-2 回归）：首尾相同、中段不同 → key 必须不同。

    原缺陷：key 只取首-尾两点，不同触发评论的 chain 吃掉中段楼层后 remote 首尾不变、
    中段不同——B 命中 A 的摘要，注入与现场不符的旧集合。
    """
    from quanta_bot.pipeline.context import _summary_cache_key

    base = [_floor(i, None, f"楼{i}") for i in range(1, 6)]
    set_a = base
    set_b = [node for node in base if node.comment_id != 3]
    key_a = _summary_cache_key(10, set_a, "pv")
    key_b = _summary_cache_key(10, set_b, "pv")
    assert key_a != key_b


async def test_channel_c_summarizes_with_cache_reuse() -> None:
    """C 通道：五项清单摘要+缓存复用（同帖第二条 @ 不再调 LLM）；from_cache 观测标记诚实。"""
    llm = FakeLLM(
        responses=[
            '{"topic":"选课","conclusions":[],"disputes":[],'
            '"unanswered_questions":["几号出课表"],"key_facts":["6月30号截止"]}'
        ]
    )
    summarizer = LLMSummarizer(llm)
    cache = InMemoryKV()
    remote = [_floor(i, None, f"远区{i}谈选课") for i in range(100, 120)]
    first, _, cache_hit_first = await build_channel_c(
        remote, summarizer, cache, post_id=10, persona_version="v1", summary_cache_ttl_hours=24
    )
    assert "6月30号截止" in first and "几号出课表" in first  # 清单关键项进摘要
    assert "仅供参考，以触发评论与父链原文为准" in first  # 非授权声明（蓝图 §5.3）
    assert cache_hit_first is False  # 首次=新摘要非缓存
    second, _, cache_hit_second = await build_channel_c(
        remote, summarizer, cache, post_id=10, persona_version="v1", summary_cache_ttl_hours=24
    )
    assert len(llm.calls) == 1  # 缓存命中，零额外调用
    assert first == second
    assert cache_hit_second is True  # 二次=缓存命中（used_summary_cache 观测口径）


async def test_channel_c_malformed_retries_then_drops() -> None:
    """摘要畸形喂回一次→仍畸形→丢弃远区+留痕（静默优于乱回）；from_cache=False。"""
    llm = FakeLLM(responses=["坏JSON", "还是坏"])
    summarizer = LLMSummarizer(llm)
    text, truncations, cache_hit = await build_channel_c(
        [_floor(1, None, "远区")], summarizer, InMemoryKV(), 10, "v1", 24
    )
    assert text == "" and cache_hit is False
    assert any("远区丢弃" in t.reason for t in truncations)


async def test_channel_c_llm_failure_drops_remote_and_continues() -> None:
    """摘要 LLM 超时/限流时丢弃远区，不让异常击穿整条回复。"""

    class FailingLLM:
        async def complete(
            self,
            system: str,
            user: str,
            *,
            json_mode: bool = False,
            max_tokens: int | None = None,
        ) -> None:
            raise LLMClientError("摘要请求超时")

    text, truncations, cache_hit = await build_channel_c(
        [_floor(1, None, "远区")],
        LLMSummarizer(FailingLLM()),
        InMemoryKV(),
        10,
        "v1",
        24,
    )
    assert text == "" and cache_hit is False
    assert any("远区丢弃" in truncation.reason for truncation in truncations)


async def test_assemble_respects_total_budget() -> None:
    """总装：总量 ≤ TOTAL_CONTEXT_BUDGET；祖先链零截断；砍序 D→C→AI历史区丢最旧；触发行永在场。"""
    chain_node = _floor(4, 3, "祖先链内容唯一标记" * 20)  # 180 字祖先链（B 通道保真区）
    remote_floors = [
        _floor(comment_id, None, f"远区楼层{comment_id}谈选课") for comment_id in range(100, 140)
    ]
    thread = PostThread(
        post=PostSummary(postId=1, userId=2, title="t", content="主楼内容" * 1500),  # 6000 字主楼
        chain=(chain_node,),
        bot_history=(
            CommentNode(
                commentId=99, parentId=None, userId=1, content="AI历史发言" * 1200
            ),  # 6000 字
        ),
    )
    memory_text = "记忆材料" * 1500  # 6000 字 D 通道材料（B+历史+C+D 四路合计远超总预算）
    llm = FakeLLM(
        responses=[
            '{"topic":"选课","conclusions":[],"disputes":[],"unanswered_questions":[],"key_facts":[]}'
        ]
    )
    assembled = await assemble(
        _event(),
        thread,
        [*remote_floors, chain_node],
        memory_text=memory_text,
        memory_truncations=(),
        summarizer=LLMSummarizer(llm),
        summary_cache=InMemoryKV(),
        persona_version="v1",
        summary_cache_ttl_hours=24,
    )
    assert len(assembled.user_text) <= TOTAL_CONTEXT_BUDGET  # 注入总量上界
    assert "祖先链内容唯一标记" * 20 in assembled.user_text  # 祖先链原文零截断（红线）
    assert assembled.user_text.endswith("触发评论：@框框 hi")  # 触发行永在场（红线）
    assert any(t.channel == "D" for t in assembled.truncations)  # 第一刀砍 D
    assert any(t.channel == "C" for t in assembled.truncations)  # 第二刀砍 C
    assert any("AI历史区" in t.what for t in assembled.truncations)  # 第三刀丢最旧 AI 历史


async def test_assemble_history_overflow_keeps_newest_and_trigger() -> None:
    """AI 历史区超总预算丢最旧保最新；对话链注入行（extra）比 bot 老楼层后丢；触发行在场。"""
    old_bots = tuple(
        CommentNode(
            commentId=bot_id, parentId=None, userId=1, content=f"最旧bot历史{bot_id}标记" * 300
        )
        for bot_id in range(10, 13)  # 3 条各 ~3300 字老历史（合计 ~9900）
    )
    newest_bot = CommentNode(commentId=99, parentId=None, userId=1, content="最新bot历史标记")
    thread = PostThread(
        post=PostSummary(postId=1, userId=2, title="t", content="主楼内容" * 750),  # 3000 字主楼
        chain=(),
        bot_history=(*old_bots, newest_bot),  # 合计 ~13000 超总预算
    )
    assembled = await assemble(
        _event(),
        thread,
        [],
        memory_text="",
        memory_truncations=(),
        summarizer=LLMSummarizer(FakeLLM()),
        summary_cache=InMemoryKV(),
        persona_version="v1",
        summary_cache_ttl_hours=24,
        extra_history_lines=("【AI回复#77】对话链注入行唯一标记",),
    )
    assert len(assembled.user_text) <= TOTAL_CONTEXT_BUDGET
    assert "最旧bot历史10标记" not in assembled.user_text  # 最老历史先丢
    assert "最新bot历史标记" in assembled.user_text  # 最新 bot 历史保留
    assert "对话链注入行唯一标记" in assembled.user_text  # extra（近对话）最后丢，仍在场
    assert assembled.user_text.endswith("触发评论：@框框 hi")  # 触发行永在场
    assert any("AI历史区" in t.what for t in assembled.truncations)  # 丢最旧留痕


async def test_assemble_no_double_render_and_history_present() -> None:
    """链/bot_history 节点不双重渲染（近区与远区都排除）；AI 历史区含 bot_history 与对话链注入行。"""
    chain_node = _floor(4, 3, "祖先链节点内容唯一标记")
    bot_node = CommentNode(
        commentId=99, parentId=None, userId=1, content="bot 历史发言唯一标记", is_ai=True
    )
    old_bot = CommentNode(
        commentId=1, parentId=None, userId=1, content="远区bot楼层唯一标记", is_ai=True
    )  # 老 bot 楼层落远区（一级楼降序 [99,5] 进近区，1 号在远区）
    floors = [old_bot, chain_node, _floor(5, None, "普通近区楼"), bot_node]
    thread = PostThread(
        post=PostSummary(postId=1, userId=2, title="t", content="主楼"),
        chain=(chain_node,),
        bot_history=(old_bot, bot_node),
    )
    llm = FakeLLM()
    assembled = await assemble(
        _event(),
        thread,
        floors,
        memory_text="",
        memory_truncations=(),
        summarizer=LLMSummarizer(llm),
        summary_cache=InMemoryKV(),
        persona_version="v1",
        summary_cache_ttl_hours=24,
        extra_history_lines=("【AI回复#77】对话链注入行唯一标记",),
    )
    assert assembled.user_text.count("祖先链节点内容唯一标记") == 1  # 链节点只在祖先链区出现一次
    assert (
        assembled.user_text.count("bot 历史发言唯一标记") == 1
    )  # 近区 bot 楼层只在 AI 历史区出现一次
    assert (
        assembled.user_text.count("远区bot楼层唯一标记") == 1
    )  # 远区 bot 楼层也只在 AI 历史区（不进 C 摘要）
    assert len(llm.calls) == 0  # 远区排空 bot 楼层后无远区可摘要（零 LLM 调用）
    assert "【AI回复#77】对话链注入行唯一标记" in assembled.user_text  # 对话链注入行在场
