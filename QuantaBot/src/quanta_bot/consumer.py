"""consumer —— MQ 消费者主循环（单消费者=同帖串行宿主；kill 暂停执行点）。

职责：消费 quantabot.comment.queue（C-1 契约）；TriggerEvent 契约校验（Pydantic 闸门）；
      逐条调 pipeline.run——单消费者 + prefetch=1 逐条处理，同帖天然串行（缺口2 最简实现）；
      kill 置位时暂停消费（G5）；消费异常兜底记 failed（静默不回红线 §0.3）。
边界：不建依赖（deps/control_plane 由 composition 注入）；不做投递层重试编排（demo0 侧
      retry/dlx 负责；bot 侧处理失败=记 failed + ack 丢弃，毒丸不阻塞队列）。
已知坑：kill 暂停采用「不 ack 持有消息 + sleep 轮询」——unacked 超过 RabbitMQ
      consumer_timeout（默认 30min）会被断连重投；kill 演练目标 ≤5min（M5），远小于阈值。
"""

import asyncio
import json
import logging

import aio_pika
from pydantic import ValidationError

from quanta_bot.crosscutting.killswitch import ControlPlane
from quanta_bot.crosscutting.ports import DecisionLogEntry
from quanta_bot.crosscutting.trace_context import (
    is_valid_event_id,
    resolve_trace_id,
    trace_scope,
)
from quanta_bot.pipeline.pipeline import PipelineDeps, run
from quanta_bot.pipeline.trigger import TriggerEvent

logger = logging.getLogger(__name__)

# C-1 契约拓扑（demo0 声明；bot 侧被动消费，不声明）
QUANTABOT_QUEUE = "quantabot.comment.queue"

# 断线/拓扑未就绪重连间隔（demo0 D4 未落地期间为常态——优雅等待）
_RECONNECT_SECONDS = 5.0


class CommentEventConsumer:
    """C-1 评论事件消费者（composition 注入 deps；run_forever 由 lifespan 托管）。"""

    def __init__(
        self,
        mq_url: str,
        deps: PipelineDeps,
        control_plane: ControlPlane,
        poll_seconds: float = 5.0,
    ) -> None:
        self._mq_url = mq_url
        self._deps = deps
        self._control_plane = control_plane
        self._poll_seconds = poll_seconds

    async def run_forever(self) -> None:
        """连接-消费主循环（断线重连；lifespan cancel 退出）。"""
        while True:
            try:
                connection = await aio_pika.connect(self._mq_url)  # 连接MQ服务器
                async with connection:
                    channel = await connection.channel()
                    await channel.set_qos(prefetch_count=1)
                    queue = await channel.get_queue(QUANTABOT_QUEUE, ensure=False)
                    async with queue.iterator() as queue_iter:
                        async for message in queue_iter:
                            await self._handle(message)
            except asyncio.CancelledError:
                raise
            except Exception as exc:  # 连接/拓扑未就绪：优雅等待重试
                logger.warning("MQ 消费中断（%.0fs 后重连）：%s", _RECONNECT_SECONDS, exc)
                await asyncio.sleep(_RECONNECT_SECONDS)

    async def _handle(self, message: aio_pika.IncomingMessage) -> None:
        """单条消息处理：kill 暂停 → 契约校验 → pipeline → ack。"""
        headers = getattr(message, "headers", None) or {}
        header_trace_id = _header_value(headers, "x-request-id")
        header_event_id = _header_value(headers, "x-event-id")
        body_event_id = _body_event_id(message.body)
        event_id = (
            header_event_id
            if is_valid_event_id(header_event_id)
            else body_event_id
            if is_valid_event_id(body_event_id)
            else None
        )
        trace_id = resolve_trace_id(header_trace_id, event_id)

        with trace_scope(trace_id, event_id):
            logger.info("mq_message_started")
            # kill 暂停（G5）：不 ack 持有消息等待恢复（prefetch=1 backpressure）
            while self._control_plane.snapshot.kill:
                await asyncio.sleep(self._poll_seconds)
            try:
                event = TriggerEvent.model_validate(json.loads(message.body))
            except (json.JSONDecodeError, UnicodeDecodeError, ValidationError) as exc:
                # 毒丸：契约不符丢弃 + 决策日志留痕（comment_id 未知用 0 哨兵）
                logger.warning("消息契约不符（丢弃）：%s", type(exc).__name__)
                await self._deps.audit.record(
                    DecisionLogEntry(
                        comment_id=0,
                        decision="failed",
                        mode=None,
                        reason=f"消息契约不符丢弃：{type(exc).__name__}",
                    )
                )
                await message.ack()
                return
            try:
                # Pydantic 校验后的 eventId 优先，保证旧消息无 header 时仍稳定关联。
                if event_id is None and is_valid_event_id(event.event_id):
                    with trace_scope(trace_id, event.event_id):
                        decision = await run(event, self._deps)
                else:
                    decision = await run(event, self._deps)
                logger.info(
                    "bot_pipeline_completed commentId=%s decision=%s",
                    event.comment_id,
                    decision,
                )
            except Exception as exc:  # 消费兜底：管线未覆盖异常记 failed（静默不回）
                logger.warning("管线异常兜底（记 failed）：%s", type(exc).__name__, exc_info=True)
                await self._deps.audit.record(
                    DecisionLogEntry(
                        comment_id=event.comment_id,
                        decision="failed",
                        mode=None,
                        reason=f"消费者兜底：{type(exc).__name__}",
                    )
                )
            await message.ack()  # 成功 ack 消息，确认处理完成
            logger.info("mq_message_acked commentId=%s", event.comment_id)


def _header_value(headers: object, name: str) -> object | None:
    """大小写不敏感读取单值 MQ header；重复/容器值视为不可信。"""
    if not hasattr(headers, "items"):
        return None
    matches: list[object] = []
    for key, value in headers.items():
        if isinstance(key, bytes):
            try:
                key = key.decode("ascii")
            except UnicodeDecodeError:
                continue
        if isinstance(key, str) and key.lower() == name:
            matches.append(value)
    if len(matches) != 1 or isinstance(matches[0], (list, tuple, set, dict)):
        return None
    return matches[0]


def _body_event_id(body: bytes) -> str | None:
    """仅读取 JSON 根 eventId，毒丸/其他字段不进入日志上下文。"""
    try:
        payload = json.loads(body)
    except (json.JSONDecodeError, UnicodeDecodeError, TypeError):
        return None
    if not isinstance(payload, dict):
        return None
    value = payload.get("eventId")
    return value if isinstance(value, str) else None
