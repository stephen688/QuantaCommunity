"""pipeline/context —— 上下文组装（M3：B/C/D 通道与四通道总装）。

职责：partition_floors 按父链关系分区近/远区、build_channel_b 组装 B 通道原文并保真截断；
      LLMSummarizer/build_channel_c 远区五项清单摘要（缓存复用+畸形重试一次+失败降级丢远区）；
      assemble 四通道总装（B+AI历史+C摘要+D记忆+预留，总预算校验，超限砍序 D→C→AI历史区丢
      最旧→末兜底保触发行——用户当前发言红线不可丢）。
边界：不触碰网络（评论树客户端经端口注入）；D 记忆召回渲染在 memory/user_memory.py；
      预算常量是项目级钉死契约（改动需先改 M3 计划——eval「超预算必截断」断言依据）。
已知坑：摘要缓存 key 带 persona_version（人格变版本旧摘要自动失效）；bot_history 节点在近区
      与远区都要排除（只走 AI 历史区——落远区会进 C 摘要+历史区双份，Task 7 执行发现）；
      M2 旧路径 build_context/_render 已随 Task 9 总装切换删除（唯一入口=assemble）。
"""

import json
from collections.abc import Sequence
from dataclasses import dataclass

from quanta_bot.crosscutting.ports import KeyValueStore, TruncationRecord
from quanta_bot.pipeline.ports import (
    CommentNode,
    LLMClient,
    PostThread,
    Summarizer,
)
from quanta_bot.pipeline.trigger import TriggerEvent

# ---- 预算常量（蓝图 §2.1/技术选型 §6.7.3；grill 决议：字符口径总 12000）----
TOTAL_CONTEXT_BUDGET = 12000  # 进 prompt 的总量上界（A 人格不占预算）
CHANNEL_B_BUDGET = 7000  # B 原文：主楼+祖先链+近区楼层
CHANNEL_C_BUDGET = 2500  # C 摘要：远区楼层压缩
CHANNEL_D_BUDGET = 1500  # D 记忆：精选+负面
RESERVED_BUDGET = 1000  # 预留：触发评论+格式开销+检索片段
NEAR_PARALLEL_FLOORS = 2  # 近区平行一级楼层数（分区=父链优先，非机械最近 N 楼）


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
    # 近区渲染排除祖先链与 bot_history 节点（分别走 chain_text / AI 历史区——防双重渲染）
    excluded_ids = {n.comment_id for n in thread.chain} | {n.comment_id for n in thread.bot_history}
    nearby_newest_first = sorted(nearby, key=lambda n: n.comment_id, reverse=True)
    used = len(post_text) + chain_separator + len(chain_text)
    kept: list[str] = []
    for index, node in enumerate(nearby_newest_first):
        if node.comment_id in excluded_ids:  # 已在祖先链区/AI 历史区渲染，此处跳过防重复
            continue
        line = f"【近区楼层#{node.comment_id}】{node.user_id}：{node.content}"
        if used + 1 + len(line) > CHANNEL_B_BUDGET:
            # 留痕只记真正被预算丢弃的节点：从断点索引起、排除已在他处渲染的节点（诚实留痕）
            dropped_ids = sorted(
                n.comment_id
                for n in nearby_newest_first[index:]
                if n.comment_id not in excluded_ids
            )
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


class SummaryError(Exception):
    """远区摘要失败（畸形重试一次后仍不可用——降级丢弃远区，不阻断回复）。"""


SUMMARY_SYSTEM_PROMPT = """你是楼层讨论压缩器。把给定楼层压缩成固定五项清单，只输出 JSON：
{"topic": "主要话题", "conclusions": ["达成的结论/共识"], "disputes": ["未决争执"],
 "unanswered_questions": ["未被回答的提问——必须逐条枚举，不许遗漏"],
 "key_facts": ["关键事实（时间/地点/政策名/数字）——必须带楼层原词，禁止改写"]}
规则：数字与事实必须来自楼层原文，不得重新估算或推断；没有的项给空数组，不编造。"""


def _parse_summary(content: str) -> dict:
    """解析摘要 JSON（形状校验：必须是 object 且五键齐全；不符统一 SummaryError）。

    两路径（首次/retry）共用——原 retry 只 except JSONDecodeError，合法 JSON 但非 dict
    （如 list）会从 _render 抛 AttributeError 逃逸，击穿 build_channel_c 的降级与管线
    failed 分支，落 consumer 兜底丢整条回复（reviewer 实证，违背「丢远区照常回复」契约）。
    """
    try:
        parsed = json.loads(content)
    except json.JSONDecodeError as exc:
        raise SummaryError(f"摘要 JSON 解析失败：{exc}") from exc
    if not isinstance(parsed, dict):
        raise SummaryError(f"摘要 JSON 非 object：{type(parsed).__name__}")
    for key in ("topic", "conclusions", "disputes", "unanswered_questions", "key_facts"):
        if key not in parsed:
            raise SummaryError(f"缺字段 {key}")
    return parsed


class LLMSummarizer:
    """远区摘要（轻量调用；畸形/畸形形状喂回重试一次，仍畸形 SummaryError）。"""

    def __init__(self, llm: LLMClient) -> None:
        self._llm = llm

    async def summarize(self, floors: Sequence[CommentNode]) -> str:
        floors_text = "\n".join(
            f"#{floor.comment_id} {floor.user_id}：{floor.content}" for floor in floors
        )
        first = await self._llm.complete(
            SUMMARY_SYSTEM_PROMPT, floors_text, json_mode=True, max_tokens=4000
        )  # 推理模型思考计入上限（500 曾被烧穿出空内容，实证修正）
        try:
            parsed = _parse_summary(first.content)
        except SummaryError:
            pass  # 喂回自愈重试一次（蓝图 §5.5 内部中间产物）
        else:
            return self._render(parsed)
        retry = await self._llm.complete(
            SUMMARY_SYSTEM_PROMPT,
            f"{floors_text}\n【上次输出畸形】{first.content}\n请只输出完整合法 JSON（五项字段齐全）。",
            json_mode=True,
            max_tokens=4000,
        )
        return self._render(_parse_summary(retry.content))  # 仍畸形 → SummaryError（build_channel_c 降级丢远区）

    @staticmethod
    def _render(parsed: dict) -> str:
        lines = [
            f"话题：{parsed.get('topic', '')}",
            f"结论：{'；'.join(parsed.get('conclusions', [])) or '无'}",
            f"未决争执：{'；'.join(parsed.get('disputes', [])) or '无'}",
            f"未被回答的提问：{'；'.join(parsed.get('unanswered_questions', [])) or '无'}",
            f"关键事实（原词）：{'；'.join(parsed.get('key_facts', [])) or '无'}",
        ]
        header = "—— 楼层摘要（仅供参考，以触发评论与父链原文为准；摘要中数字来自楼层原文）——"
        return header + "\n" + "\n".join(lines)


def _summary_cache_key(post_id: int, floors: Sequence[CommentNode], persona_version: str) -> str:
    ids = [floor.comment_id for floor in floors]
    return f"quantabot:summary:{post_id}:{ids[0] if ids else 0}-{ids[-1] if ids else 0}:{persona_version}"


async def build_channel_c(
    remote: Sequence[CommentNode],
    summarizer: Summarizer,
    summary_cache: KeyValueStore,
    post_id: int,
    persona_version: str,
    summary_cache_ttl_hours: int,
) -> tuple[str, tuple[TruncationRecord, ...], bool]:
    """C 通道：远区摘要（缓存 key 带 persona_version——人格变版本旧摘要自动失效，蓝图 §5.4）。

    返回 (摘要文本, 留痕, from_cache)——from_cache 供 assemble 透传 used_summary_cache（缓存
    复用率观测；2026-09-17 修正：原二元组使该标记只能推断，C 被总预算砍掉时误报 True）。
    """
    if not remote:
        return "", (), False
    key = _summary_cache_key(post_id, remote, persona_version)
    cached = await summary_cache.get(key)
    if cached is not None:  # 缓存命中：零 LLM 调用（同帖第二条 @ 直接复用）
        return cached, (), True
    try:
        summary = await summarizer.summarize(remote)
    except SummaryError:  # 失败降级=丢弃远区照常回复（静默优于乱回）
        return (
            "",
            (
                TruncationRecord(
                    channel="C",
                    what="远区全部楼层",
                    reason="摘要两次失败，远区丢弃",
                    chars_dropped=0,
                ),
            ),
            False,
        )
    if len(summary) > CHANNEL_C_BUDGET:  # 摘要自身超预算截尾留痕（标记计入预算——与 truncate_head_tail 口径一致）
        marker = "（摘要超限截断）"
        summary = summary[: CHANNEL_C_BUDGET - len(marker)] + marker
    await summary_cache.set(key, summary, summary_cache_ttl_hours * 3600)
    return summary, (), False


@dataclass
class AssembledContext:
    """四通道总装结果（user_text 进生成层；truncations 进 RunTrace 对质用）。"""

    user_text: str
    truncations: tuple[TruncationRecord, ...] = ()
    used_summary_cache: bool = False
    retrieval_degraded: bool = False


async def assemble(
    event: TriggerEvent,
    thread: PostThread,
    floors: Sequence[CommentNode],
    memory_text: str,
    memory_truncations: tuple[TruncationRecord, ...],
    summarizer: Summarizer,
    summary_cache: KeyValueStore,
    persona_version: str,
    summary_cache_ttl_hours: int,
    retrieval_lines: Sequence[str] = (),
    extra_history_lines: Sequence[str] = (),
) -> AssembledContext:
    """四通道总装：B(原文+AI历史)+C(摘要)+D(记忆)+预留(触发评论+检索)，总量 ≤ TOTAL_CONTEXT_BUDGET。

    2026-09-16 执行前修正（Task 6 执行发现）：①AI 历史区=thread.bot_history + extra_history_lines
    （对话级记忆链由 Task 9 去重后注入——C-2③ 权威、Redis 链兜底）；②近区楼层中与祖先链/
    bot_history 重复的节点由 build_channel_b 排除，防双重渲染。
    2026-09-17 Task 7 执行后修正：③远区同样排除 bot_history 节点（bot 楼层只走 AI 历史区，
    否则落远区时进 C 摘要+历史区双份）；④砍序扩为 D→C→AI历史区丢最旧→末兜底保触发行
    （原 D→C 两刀+尾部盲切会把触发行切掉——用户当前发言红线不可丢）；⑤used_summary_cache=
    build_channel_c 的 from_cache 直传（原推断式语义不诚实）。
    """
    chain_ids = {node.comment_id for node in thread.chain}
    bot_ids = {node.comment_id for node in thread.bot_history}
    trigger_floor = event.parent_id  # 触发评论所在一级楼层（一级评论时=自身）
    nearby, remote = partition_floors(floors, chain_ids, trigger_floor)
    remote = [node for node in remote if node.comment_id not in bot_ids]  # ③ bot 楼层只走历史区
    b_text, b_trunc = build_channel_b(thread, nearby)  # 内部排除链/bot_history 节点（防双重渲染）
    history_lines = [
        f"【AI历史#{node.comment_id}】{node.content}" for node in thread.bot_history
    ] + list(extra_history_lines)
    c_text, c_trunc, used_cache = await build_channel_c(
        remote, summarizer, summary_cache, event.post_id, persona_version, summary_cache_ttl_hours
    )
    retrieval_text = "\n".join(
        retrieval_lines
    )  # 检索片段行（Task 13 由 pipeline 从 RetrievedFragment 渲染，软上界 RESERVED_BUDGET）
    trigger_line = f"触发评论：{event.content}"
    parts = [b_text, "\n".join(history_lines), c_text, memory_text, retrieval_text, trigger_line]
    truncations = [*b_trunc, *c_trunc, *memory_truncations]

    def _user_text() -> str:
        return "\n".join(part for part in parts if part)

    user_text = _user_text()
    if len(user_text) > TOTAL_CONTEXT_BUDGET and memory_text:  # 第一刀：D 整体
        truncations.append(
            TruncationRecord(
                channel="D",
                what="记忆通道整体",
                reason="超总预算",
                chars_dropped=len(memory_text),
            )
        )
        parts[3] = memory_text = ""
        user_text = _user_text()
    if len(user_text) > TOTAL_CONTEXT_BUDGET and c_text:  # 第二刀：C 整体
        truncations.append(
            TruncationRecord(
                channel="C", what="摘要通道整体", reason="超总预算", chars_dropped=len(c_text)
            )
        )
        parts[2] = ""
        user_text = _user_text()
    drop_count = 0
    dropped_chars = 0
    while len(user_text) > TOTAL_CONTEXT_BUDGET and history_lines:  # 第三刀：AI 历史区丢最旧
        oldest = history_lines.pop(0)  # bot 老楼层先丢；extra（对话链）在列表尾最后丢
        drop_count += 1
        dropped_chars += len(oldest)
        parts[1] = "\n".join(history_lines)
        user_text = _user_text()
    if drop_count:
        truncations.append(
            TruncationRecord(
                channel="B",
                what=f"AI历史区最旧{drop_count}行",
                reason="超总预算丢最旧",
                chars_dropped=dropped_chars,
            )
        )
    if len(user_text) > TOTAL_CONTEXT_BUDGET:
        # 末兜底（病态：B+检索自身超限）：保触发行在场，切其余部分头部（开头定话题保头）
        overflow = len(user_text) - TOTAL_CONTEXT_BUDGET
        body = "\n".join(part for part in parts[:-1] if part)
        user_text = (
            body[: max(0, TOTAL_CONTEXT_BUDGET - len(trigger_line) - 1)] + "\n" + trigger_line
        )
        if len(user_text) > TOTAL_CONTEXT_BUDGET:  # 触发行自身超总预算的极端：整体保头
            user_text = user_text[:TOTAL_CONTEXT_BUDGET]
        truncations.append(
            TruncationRecord(
                channel="B", what="总装头部", reason="末兜底保触发行", chars_dropped=overflow
            )
        )
    return AssembledContext(
        user_text=user_text,
        truncations=tuple(truncations),
        used_summary_cache=used_cache,
    )
