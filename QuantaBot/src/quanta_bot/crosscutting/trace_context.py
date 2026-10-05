"""crosscutting/trace_context —— Bot 请求关联上下文。

职责：校验/生成业务 traceId，使用 ContextVar 保存当前协程的 trace/event 编号。
边界：不读取 Settings、不建立外部连接、不负责日志输出；调用方负责在线路边界
      （HTTP/MQ）先校验不可信输入，再进入 trace_scope。
"""

import hashlib
import re
import uuid
from collections.abc import Iterator
from contextlib import contextmanager
from contextvars import ContextVar

_TRACE_ID_PATTERN = re.compile(r"[A-Za-z0-9_-]{1,64}\Z")
_EVENT_ID_PATTERN = re.compile(r"[A-Za-z0-9_.:-]{1,128}\Z")
_trace_id: ContextVar[str | None] = ContextVar("trace_id", default=None)
_event_id: ContextVar[str | None] = ContextVar("event_id", default=None)


def _safe_text(candidate: object, max_bytes: int) -> str | None:
    """把外部 header 转成受限 ASCII 文本；非法输入返回 None。"""
    if isinstance(candidate, bytes):
        if len(candidate) > max_bytes:
            return None
        try:
            candidate = candidate.decode("ascii")
        except UnicodeDecodeError:
            return None
    if not isinstance(candidate, str):
        return None
    try:
        if len(candidate.encode("ascii")) > max_bytes:
            return None
    except UnicodeEncodeError:
        return None
    return candidate


def is_valid_event_id(candidate: object) -> bool:
    """返回 eventId 是否符合 MQ 关联字段的安全字符集。"""
    value = _safe_text(candidate, 128)
    return value is not None and _EVENT_ID_PATTERN.fullmatch(value) is not None


def resolve_trace_id(candidate: object, event_id: str | None = None) -> str:
    """保留合法 traceId，否则按合法 eventId 稳定生成或随机生成。"""
    value = _safe_text(candidate, 64)
    if value is not None and _TRACE_ID_PATTERN.fullmatch(value) is not None:
        return value
    event_value = _safe_text(event_id, 128)
    if event_value is not None and _EVENT_ID_PATTERN.fullmatch(event_value) is not None:
        return hashlib.sha256(f"event:{event_value}".encode("utf-8")).hexdigest()[:32]
    return uuid.uuid4().hex


def current_trace_id() -> str | None:
    """返回当前协程的业务 traceId；上下文外返回 None。"""
    return _trace_id.get()


def current_event_id() -> str | None:
    """返回当前协程的 eventId；上下文外返回 None。"""
    return _event_id.get()


@contextmanager
def trace_scope(trace_id: str, event_id: str | None = None) -> Iterator[None]:
    """临时安装 trace/event 上下文，并在退出时恢复调用方原值。"""
    trace_token = _trace_id.set(trace_id)
    event_token = _event_id.set(event_id)
    try:
        yield
    finally:
        _event_id.reset(event_token)
        _trace_id.reset(trace_token)
