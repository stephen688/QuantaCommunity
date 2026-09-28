"""memory/profile_preferences —— 受控词表上的显式主题偏好纯逻辑。

职责：把已落库的 user/feedback 记忆保守映射为最多三个受控主题 ID；
      只接受明确的喜欢/厌恶表达，不调用模型、不推断学院年级等个人事实。
边界：词表由主服务通过 HTTP 提供，本模块不定义业务词表；事件持久化与发送在 infra。
"""

import re
from collections.abc import Iterable
from dataclasses import dataclass

MAX_PROFILE_TOPICS = 3

# 负面短语先单独识别；不能因为“喜欢”子串出现在“不喜欢”里而误判极性。
_POSITIVE_MARKERS = (
    "喜欢",
    "爱看",
    "偏好",
    "感兴趣",
    "想看",
    "想了解",
    "多推荐",
    "推荐我",
)
_NEGATIVE_MARKERS = (
    "不喜欢",
    "讨厌",
    "反感",
    "不想看",
    "不想要",
    "别推荐",
    "不要推荐",
    "没兴趣",
    "不感兴趣",
    "避开",
)

_MARKER_RE = re.compile(
    "|".join(
        re.escape(marker)
        for marker in sorted((*_POSITIVE_MARKERS, *_NEGATIVE_MARKERS), key=len, reverse=True)
    )
)
_CLAUSE_BOUNDARY_RE = re.compile(r"[，,。！？!?；;：:\n]|但是|不过|然而|而非|只是|却")
_LIST_JOINER_RE = re.compile(r"(?:、|，|,|和|与|及|以及|还有|也)")
_TOPIC_PREFIX_RE = re.compile(r"(?:看|聊|学|了解|关注|研究|推荐|关于|对|玩|读|用)")


@dataclass(frozen=True, slots=True)
class TopicDefinition:
    """主服务词表中的一个主题；ID 是跨服务传输的唯一值。"""

    id: str
    label: str
    aliases: tuple[str, ...] = ()


def extract_preferences(
    content: str,
    valence: str | None,
    topics: Iterable[TopicDefinition],
) -> tuple[str, ...]:
    """从记忆文本提取明确主题偏好，最多返回三个受控 ID。

    只要文本同时出现正负表达就保守放弃整条记忆，避免把矛盾表述扩散成错误画像。
    返回顺序跟主服务词表一致，保证同一快照重复计算得到相同事件内容。
    """
    topic_catalog = tuple(topics)
    normalized_content = _normalize(content)
    if not normalized_content:
        return ()

    detected_valence = infer_preference_valence(content, valence)
    if detected_valence is None:
        return ()
    markers = tuple(_marker_matches(normalized_content))

    matched_ids: set[str] = set()
    for clause_start, clause_end in _clause_ranges(normalized_content):
        clause_markers = [marker for marker in markers if clause_start <= marker[1] < clause_end]
        for marker_index, (_, marker_start, marker_end) in enumerate(clause_markers):
            next_marker_start = (
                clause_markers[marker_index + 1][1]
                if marker_index + 1 < len(clause_markers)
                else clause_end
            )
            # 只解析 marker 之后的局部短语；marker 前的“主题+我喜欢”形式另作
            # 保守的尾部匹配，避免整句主题碰撞。
            after_marker = normalized_content[marker_end:next_marker_start]
            local_matches = _topic_chain(
                after_marker, topic_catalog, negative=detected_valence == "negative"
            )
            matched_ids.update(local_matches)
            if not local_matches:
                before_marker = normalized_content[clause_start:marker_start]
                matched_ids.update(
                    _topic_before_marker(
                        before_marker, topic_catalog, negative=detected_valence == "negative"
                    )
                )

    selected: list[str] = []
    for topic in topic_catalog:
        if not topic.id or topic.id not in matched_ids:
            continue
        selected.append(topic.id)
        if len(selected) == MAX_PROFILE_TOPICS:
            break
    return tuple(selected)


def infer_preference_valence(content: str, valence: str | None) -> str | None:
    """返回文字明确表达的极性，允许 user 记忆的 valence 为空。"""
    normalized_content = _normalize(content)
    markers = tuple(_marker_matches(normalized_content))
    polarities = {kind for kind, _, _ in markers}
    if not markers or len(polarities) != 1:
        return None
    detected = next(iter(polarities))
    if valence is not None and valence != detected:
        return None
    return detected


def _normalize(value: str) -> str:
    """大小写与空白归一；不做模糊分词，避免窄厌恶扩成整类。"""
    return re.sub(r"\s+", "", value.casefold())


def _contains_marker(content: str, markers: Iterable[str]) -> bool:
    return any(_normalize(marker) in content for marker in markers)


def _marker_matches(content: str) -> Iterable[tuple[str, int, int]]:
    for match in _MARKER_RE.finditer(content):
        marker = match.group(0)
        kind = "negative" if marker in _NEGATIVE_MARKERS else "positive"
        yield kind, match.start(), match.end()


def _clause_ranges(content: str) -> Iterable[tuple[int, int]]:
    start = 0
    for boundary in _CLAUSE_BOUNDARY_RE.finditer(content):
        if start < boundary.start():
            yield start, boundary.start()
        start = boundary.end()
    if start < len(content):
        yield start, len(content)


def _topic_chain(content: str, topics: Iterable[TopicDefinition], *, negative: bool) -> set[str]:
    """Parse a short marker-bound list, stopping at unrelated prose.

    The first topic must start immediately after the marker (or one conservative
    verb such as ``看``/``了解``); subsequent topics may be joined by list words.
    This is intentionally narrower than searching the whole memory string.
    """
    if not content:
        return set()
    by_term = _topic_terms(topics, negative=negative)
    cursor = 0
    while True:
        prefix_match = _TOPIC_PREFIX_RE.match(content, cursor)
        if prefix_match:
            cursor = prefix_match.end()
        match = _term_at(content, cursor, by_term)
        if match is None:
            return set()
        end, topic_id = match
        selected = {topic_id}
        cursor = end
        while True:
            joiner = _LIST_JOINER_RE.match(content, cursor)
            if joiner is None:
                return selected if not content[cursor:] else set()
            cursor = joiner.end()
            match = _term_at(content, cursor, by_term)
            if match is None:
                return selected if not content[cursor:] else set()
            end, topic_id = match
            selected.add(topic_id)
            cursor = end


def _topic_before_marker(
    content: str, topics: Iterable[TopicDefinition], *, negative: bool
) -> set[str]:
    by_term = _topic_terms(topics, negative=negative)
    for term, topic_id in by_term:
        if content.endswith(term):
            return {topic_id}
    return set()


def _topic_terms(
    topics: Iterable[TopicDefinition], *, negative: bool
) -> tuple[tuple[str, str], ...]:
    terms: list[tuple[str, str]] = []
    for topic in topics:
        if not topic.id:
            continue
        # 主服务 aliases 面向正向内容打标，可能包含窄词（如“面试”“电影”）；
        # 负向画像只接受整类 label/id，避免把窄厌恶扩大成父类厌恶。
        terms_for_polarity = (
            (topic.label, topic.id)
            if negative
            else (
                topic.label,
                topic.id,
                *topic.aliases,
            )
        )
        for term in terms_for_polarity:
            normalized = _normalize(term)
            if normalized:
                terms.append((normalized, topic.id))
    return tuple(sorted(set(terms), key=lambda item: len(item[0]), reverse=True))


def _term_at(
    content: str, cursor: int, terms: tuple[tuple[str, str], ...]
) -> tuple[int, str] | None:
    for term, topic_id in terms:
        if content.startswith(term, cursor):
            return cursor + len(term), topic_id
    return None
