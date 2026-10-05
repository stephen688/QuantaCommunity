"""Bot 日志配置行为测试：上下文字段与有界文件轮转。"""

import gzip
import logging
from pathlib import Path

from quanta_bot.crosscutting.trace_context import trace_scope
from quanta_bot.infra.logging_config import SizeAndTimeRotatingFileHandler


def test_rotating_handler_archives_by_size_without_overwriting(tmp_path: Path) -> None:
    """小阈值触发轮转后产生 gzip 归档，第二次轮转不覆盖第一份。"""
    log_path = tmp_path / "quantabot.log"
    handler = SizeAndTimeRotatingFileHandler(
        log_path,
        max_bytes=32,
        retention_days=14,
        total_size_bytes=1024 * 1024,
    )
    handler.setFormatter(logging.Formatter("%(message)s"))
    logger = logging.getLogger("test.logging.rotation")
    logger.handlers = [handler]
    logger.setLevel(logging.INFO)
    logger.propagate = False
    try:
        logger.info("first message that rolls")
        logger.info("second message that rolls")
        logger.info("third message")
    finally:
        handler.close()
        logger.handlers = []

    archives = sorted(tmp_path.glob("quantabot.log.*.gz"))
    assert len(archives) >= 2
    assert len({archive.name for archive in archives}) == len(archives)
    assert "first" in gzip.open(archives[0], "rt", encoding="utf-8").read()


def test_context_filter_captures_trace_before_queue(tmp_path: Path) -> None:
    """日志记录创建时捕获 trace/event，后台 handler 不依赖后续 ContextVar。"""
    from quanta_bot.infra.logging_config import TraceContextFilter

    record = logging.LogRecord("test", logging.INFO, __file__, 1, "message", (), None)
    with trace_scope("request-A", "event-1"):
        assert TraceContextFilter().filter(record)

    assert TraceContextFilter().filter(record)
    assert record.traceId == "request-A"
    assert record.eventId == "event-1"
