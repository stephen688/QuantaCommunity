"""infra/logging_config —— QuantaBot 控制台与有界文件日志。

职责：在进程启动时安装一个 QueueHandler、控制台输出和可选的 UTC 日/大小轮转文件。
      LogRecord 在入队前捕获 ContextVar，避免后台写文件线程读到别的请求上下文。
边界：不负责集中日志平台、业务日志内容或审计数据库；文件归档只匹配本模块生成的
      gzip 文件，不递归删除目录，也不触碰 SQLite/其他应用日志。
"""

from __future__ import annotations

import gzip
import logging
import logging.handlers
import re
import shutil
import sys
import time
from dataclasses import dataclass
from datetime import UTC, date, datetime, timedelta
from pathlib import Path
from queue import Queue

from quanta_bot.crosscutting.trace_context import current_event_id, current_trace_id
from quanta_bot.infra.settings import Settings, resolve_data_path

_SERVICE_NAME = "quantabot"
_LOG_FORMAT = (
    "%(asctime)s.%(msecs)03dZ %(levelname)-5s service=quantabot "
    "thread=%(threadName)s traceId=%(traceId)s eventId=%(eventId)s "
    "logger=%(name)s %(message)s"
)
_DATE_FORMAT = "%Y-%m-%dT%H:%M:%S"
_active_runtime: "LoggingRuntime | None" = None


class TraceContextFilter(logging.Filter):
    """在日志进入 QueueHandler 时把当前协程上下文复制到 LogRecord。"""

    def filter(self, record: logging.LogRecord) -> bool:
        # QueueHandler 已在生产协程捕获过的字段不能被 QueueListener 线程的空上下文覆盖。
        if not hasattr(record, "traceId"):
            record.traceId = current_trace_id() or "-"
        if not hasattr(record, "eventId"):
            record.eventId = current_event_id() or "-"
        return True


class _UTCFormatter(logging.Formatter):
    """用 UTC 输出时间，避免应用机器时区不同导致排查顺序混乱。"""

    converter = time.gmtime


class SizeAndTimeRotatingFileHandler(logging.Handler):
    """标准库实现的 UTC 日切 + 大小轮转 gzip 文件 handler。"""

    _ARCHIVE_TIME_FORMAT = "%Y%m%dT%H%M%SZ"
    terminator = "\n"

    def __init__(
        self,
        filename: str | Path,
        *,
        max_bytes: int,
        retention_days: int,
        total_size_bytes: int,
        encoding: str = "utf-8",
    ) -> None:
        super().__init__()
        if max_bytes <= 0 or retention_days <= 0 or total_size_bytes <= 0:
            raise ValueError("日志轮转阈值必须为正数")
        self._filename = Path(filename)
        self._max_bytes = max_bytes
        self._retention_days = retention_days
        self._total_size_bytes = total_size_bytes
        self._encoding = encoding
        self._stream = None
        self._disabled = False
        self._archive_pattern = re.compile(
            rf"^{re.escape(self._filename.name)}\."
            rf"(?P<timestamp>\d{{8}}T\d{{6}}Z)\.(?P<index>\d+)\.gz$"
        )
        self._filename.parent.mkdir(parents=True, exist_ok=True)
        self._open_stream()
        self._active_date = self._file_utc_date()
        self._prune_archives(datetime.now(UTC))

    @property
    def filename(self) -> Path:
        """当前活动日志文件路径。"""
        return self._filename

    def _open_stream(self) -> None:
        self._stream = self._filename.open("a", encoding=self._encoding, newline="")

    def _file_utc_date(self) -> date:
        if self._filename.exists():
            return datetime.fromtimestamp(self._filename.stat().st_mtime, UTC).date()
        return datetime.now(UTC).date()

    def _should_rollover(self, encoded_length: int, now: datetime) -> bool:
        if now.date() != self._active_date:
            return self._filename.stat().st_size > 0
        return self._filename.stat().st_size > 0 and (
            self._filename.stat().st_size + encoded_length > self._max_bytes
        )

    def emit(self, record: logging.LogRecord) -> None:
        """写一条已捕获上下文的记录，失败时保留其他 handler 的控制台输出。"""
        if self._disabled:
            return
        try:
            message = self.format(record)
            encoded_length = len((message + self.terminator).encode(self._encoding))
            now = datetime.now(UTC)
            if self._should_rollover(encoded_length, now):
                self.doRollover(now)
            assert self._stream is not None
            self._stream.write(message + self.terminator)
            self._stream.flush()
        except Exception:
            self._disabled = True
            self.handleError(record)

    def doRollover(self, now: datetime | None = None) -> None:
        """压缩当前文件并打开同名活动文件；归档名冲突时递增序号。"""
        rollover_time = now or datetime.now(UTC)
        if self._stream is not None:
            self._stream.flush()
            self._stream.close()
        self._stream = None
        if self._filename.exists() and self._filename.stat().st_size > 0:
            archive_path = self._next_archive_path(rollover_time)
            with self._filename.open("rb") as source, gzip.open(archive_path, "wb") as target:
                shutil.copyfileobj(source, target)
            self._filename.write_bytes(b"")
        self._open_stream()
        self._active_date = rollover_time.date()
        self._prune_archives(rollover_time)

    def _next_archive_path(self, now: datetime) -> Path:
        stamp = now.strftime(self._ARCHIVE_TIME_FORMAT)
        index = 1
        while True:
            candidate = self._filename.with_name(f"{self._filename.name}.{stamp}.{index}.gz")
            if not candidate.exists():
                return candidate
            index += 1

    def _archive_entries(self) -> list[tuple[datetime, Path]]:
        entries: list[tuple[datetime, Path]] = []
        for candidate in self._filename.parent.iterdir():
            match = self._archive_pattern.fullmatch(candidate.name)
            if match is None or not candidate.is_file():
                continue
            try:
                timestamp = datetime.strptime(
                    match.group("timestamp"), self._ARCHIVE_TIME_FORMAT
                ).replace(tzinfo=UTC)
            except ValueError:
                continue
            entries.append((timestamp, candidate))
        return sorted(entries, key=lambda item: (item[0], item[1].name))

    def _prune_archives(self, now: datetime) -> None:
        """删除本服务过期/超总量归档，不触碰目录中的其他文件。"""
        entries = self._archive_entries()
        expire_before = now - timedelta(days=self._retention_days)
        kept: list[tuple[datetime, Path]] = []
        for timestamp, path in entries:
            if timestamp < expire_before:
                path.unlink(missing_ok=True)
            else:
                kept.append((timestamp, path))
        total_size = sum(path.stat().st_size for _, path in kept if path.exists())
        for _timestamp, path in kept:
            if total_size <= self._total_size_bytes:
                break
            if path.exists():
                total_size -= path.stat().st_size
                path.unlink(missing_ok=True)

    def handleError(self, record: logging.LogRecord) -> None:
        """报告脱敏的文件写入故障，不把异常再次写回同一文件。"""
        try:
            sys.stderr.write(f"{_SERVICE_NAME} file logging disabled: {self._filename}\n")
            sys.stderr.flush()
        except OSError:
            pass

    def close(self) -> None:
        try:
            if self._stream is not None:
                self._stream.flush()
                self._stream.close()
                self._stream = None
        finally:
            super().close()


@dataclass
class LoggingRuntime:
    """当前进程安装的日志资源，lifespan 退出时只关闭自己创建的对象。"""

    root_logger: logging.Logger
    queue_handler: logging.handlers.QueueHandler
    listener: logging.handlers.QueueListener
    owned_handlers: tuple[logging.Handler, ...]
    closed: bool = False

    def close(self) -> None:
        """先排空队列，再移除/关闭本次安装的 handler。"""
        if self.closed:
            return
        self.listener.stop()
        self.root_logger.removeHandler(self.queue_handler)
        self.queue_handler.close()
        for handler in self.owned_handlers:
            handler.close()
        self.closed = True


def _level(value: str) -> int:
    level = logging.getLevelName(value.upper())
    if not isinstance(level, int):
        raise ValueError(f"未知日志级别：{value}")
    return level


def configure_logging(settings: Settings) -> LoggingRuntime:
    """按 Settings 安装日志资源；空 log_file 时只保留控制台。"""
    global _active_runtime
    if _active_runtime is not None:
        _active_runtime.close()
        _active_runtime = None

    root_logger = logging.getLogger()
    root_logger.setLevel(_level(str(getattr(settings, "log_level", "INFO"))))
    trace_filter = TraceContextFilter()
    queue: Queue[logging.LogRecord] = Queue()
    queue_handler = logging.handlers.QueueHandler(queue)
    queue_handler.addFilter(trace_filter)

    formatter = _UTCFormatter(_LOG_FORMAT, datefmt=_DATE_FORMAT)
    console_handler = logging.StreamHandler(sys.stderr)
    console_handler.setFormatter(formatter)
    console_handler.addFilter(trace_filter)
    owned_handlers: list[logging.Handler] = [console_handler]

    log_file_value = str(getattr(settings, "log_file", ""))
    if log_file_value:
        try:
            file_handler = SizeAndTimeRotatingFileHandler(
                resolve_data_path(log_file_value),
                max_bytes=settings.log_max_bytes,
                retention_days=settings.log_retention_days,
                total_size_bytes=settings.log_total_size_bytes,
            )
            file_handler.setFormatter(formatter)
            file_handler.addFilter(trace_filter)
            owned_handlers.append(file_handler)
        except (OSError, ValueError) as exc:
            sys.stderr.write(f"{_SERVICE_NAME} file logging unavailable: {type(exc).__name__}\n")

    listener = logging.handlers.QueueListener(queue, *owned_handlers, respect_handler_level=True)
    root_logger.addHandler(queue_handler)
    runtime = LoggingRuntime(root_logger, queue_handler, listener, tuple(owned_handlers))
    listener.start()
    _active_runtime = runtime
    return runtime


def shutdown_logging(runtime: LoggingRuntime | None) -> None:
    """安全关闭指定日志资源，避免误关其他库安装的 handler。"""
    global _active_runtime
    if runtime is not None:
        runtime.close()
    if _active_runtime is runtime:
        _active_runtime = None
