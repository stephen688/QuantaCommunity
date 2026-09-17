"""pipeline/context —— 上下文组装（M2：渲染线程文本；M3：B 通道分区+保真截断）。

职责：build_context 渲染主楼/父链/AI 历史文本（M2 旧路径，Task 9 总装时切 assemble）；
      partition_floors 按父链关系分区近/远区、build_channel_b 组装 B 通道原文并保真截断。
边界：不触碰网络（评论树客户端经端口注入）；C 摘要/D 记忆通道与四通道总装在 Task 7/9 落地；
      预算常量是项目级钉死契约（改动需先改 M3 计划——eval「超预算必截断」断言依据）。
"""

from collections.abc import Sequence

from quanta_bot.crosscutting.ports import TruncationRecord
from quanta_bot.pipeline.ports import CommentNode, CommentTreeFetcher, PostThread
from quanta_bot.pipeline.trigger import TriggerEvent

# ---- 预算常量（蓝图 §2.1/技术选型 §6.7.3；grill 决议：字符口径总 12000）----
TOTAL_CONTEXT_BUDGET = 12000  # 进 prompt 的总量上界（A 人格不占预算）
CHANNEL_B_BUDGET = 7000  # B 原文：主楼+祖先链+近区楼层
CHANNEL_C_BUDGET = 2500  # C 摘要：远区楼层压缩
CHANNEL_D_BUDGET = 1500  # D 记忆：精选+负面
RESERVED_BUDGET = 1000  # 预留：触发评论+格式开销+检索片段
NEAR_PARALLEL_FLOORS = 2  # 近区平行一级楼层数（分区=父链优先，非机械最近 N 楼）


# 渲染线程文本
def _render(thread: PostThread) -> str:
    lines = [f"【主楼#{thread.post.post_id}】{thread.post.title}：{thread.post.content}"]
    for node in thread.chain:
        ai_tag = "[AI]" if node.is_ai else ""
        lines.append(f"【评论#{node.comment_id}】{ai_tag}{node.user_id}：{node.content}")
    for node in thread.bot_history:
        lines.append(f"【AI历史#{node.comment_id}】{node.content}")
    return "\n".join(lines)


async def build_context(event: TriggerEvent, fetcher: CommentTreeFetcher) -> str:
    """拉取线程并渲染上下文文本（M3 起做筛选与长度预算）。"""
    return _render(await fetcher.fetch_context(event))


def partition_floors(
    floors: Sequence[CommentNode],
    chain_comment_ids: set[int],
    trigger_parent_floor_id: int | None,
) -> tuple[list[CommentNode], list[CommentNode]]:
    """近远区分区：祖先链所在一级楼层（含楼中楼）+ 最新 NEAR_PARALLEL_FLOORS 个平行楼=近区；其余=远区。"""
    # 链上节点自身 + 它们的父级一级楼层都算"链所在楼层"（2026-09-16 执行前修正：
    # 只归集 parent 会把"对链节点的直接回复"掉进远区被摘要——楼中楼挂靠关系必须完整进近区）
    floor_ids_on_chain = set(chain_comment_ids)
    for node in floors:
        if node.comment_id in chain_comment_ids and node.parent_id is not None:
            floor_ids_on_chain.add(node.parent_id)
    if trigger_parent_floor_id is not None:
        floor_ids_on_chain.add(trigger_parent_floor_id)
    top_level = [node for node in floors if node.parent_id is None]
    near_floor_ids = set(floor_ids_on_chain)
    for node in sorted(top_level, key=lambda n: n.comment_id, reverse=True)[:NEAR_PARALLEL_FLOORS]:
        near_floor_ids.add(node.comment_id)  # 最新平行楼（一级）进近区
    nearby = [n for n in floors if n.parent_id in near_floor_ids or n.comment_id in near_floor_ids]
    nearby_ids = {n.comment_id for n in nearby}
    remote = [n for n in floors if n.comment_id not in nearby_ids]
    return nearby, remote


def truncate_head_tail(text: str, budget: int) -> str:
    """保头尾掐中间（主楼超限专用——开头定话题、结尾常带结论；截断标记计入预算）。"""
    if len(text) <= budget:
        return text
    marker = "……（中间截断）……"
    half = max(1, (budget - len(marker)) // 2)
    return f"{text[:half]}{marker}{text[-half:]}"


def build_channel_b(
    thread: PostThread, nearby: Sequence[CommentNode]
) -> tuple[str, tuple[TruncationRecord, ...]]:
    """B 通道：主楼+祖先链+近区楼层，保真截断（祖先链一字不砍；平行从最老丢；主楼掐中间）。"""
    truncations: list[TruncationRecord] = []
    # ① 祖先链渲染——一字不砍（红线：链差一个字就接不上话茬，预算再紧也不动它）
    chain_text = "\n".join(
        f"【评论#{n.comment_id}】{'[AI]' if n.is_ai else ''}{n.user_id}：{n.content}"
        for n in thread.chain
    )
    post_text = f"【主楼#{thread.post.post_id}】{thread.post.title}：{thread.post.content}"
    # ② 主楼预算 = B 预算 - 祖先链 - 分隔换行；超限保头尾掐中间并留痕
    chain_separator = 1 if chain_text else 0
    remaining = CHANNEL_B_BUDGET - len(chain_text) - chain_separator
    if len(post_text) > remaining:
        dropped = len(post_text) - remaining
        post_text = truncate_head_tail(post_text, remaining)
        truncations.append(
            TruncationRecord(
                channel="B", what="主楼", reason="超B预算保头尾掐中间", chars_dropped=dropped
            )
        )
    # ③ 近区楼层从最新装起（超预算从最老丢——蓝图 §6.7.3），装不下的整批丢弃并留痕
    nearby_newest_first = sorted(nearby, key=lambda n: n.comment_id, reverse=True)
    used = len(post_text) + chain_separator + len(chain_text)
    kept: list[str] = []
    for node in nearby_newest_first:
        line = f"【近区楼层#{node.comment_id}】{node.user_id}：{node.content}"
        if used + 1 + len(line) > CHANNEL_B_BUDGET:
            dropped_ids = sorted(n.comment_id for n in nearby_newest_first[len(kept) :])
            if dropped_ids:
                truncations.append(
                    TruncationRecord(
                        channel="B",
                        what=f"平行楼层{dropped_ids}",
                        reason="超B预算从最老丢",
                        chars_dropped=0,
                    )
                )
            break
        kept.append(line)
        used += 1 + len(line)
    kept.reverse()  # 渲染恢复时间正序（旧→新）
    text = "\n".join([post_text, *kept, chain_text] if chain_text else [post_text, *kept])
    return text, tuple(truncations)
