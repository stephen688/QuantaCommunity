"""infra/profile_sync —— 显式主题偏好待同步 worker。

职责：缓存主服务受控词表，从 Qdrant pending 快照提取明确偏好并经 BOT HTTP 提交；
      HTTP 成功后按 eventId+revision 清理同一待办。
边界：不回扫历史记忆、不调用 LLM、不发送记忆原文；Qdrant 点修改与清标记由存储端口加锁，
      HTTP 调用始终在锁外执行。
"""

import asyncio
import logging
from collections.abc import Mapping

import httpx

from quanta_bot.infra.main_service import MainServiceClient, MainServiceError
from quanta_bot.memory.ports import (
    PendingProfileSync,
    ProfileSyncEvent,
    ProfileSyncStore,
)
from quanta_bot.memory.profile_preferences import (
    TopicDefinition,
    extract_preferences,
    infer_preference_valence,
)

logger = logging.getLogger(__name__)

_DEFAULT_BATCH_SIZE = 50
_DEFAULT_POLL_SECONDS = 5.0


class ProfileSyncWorker:
    """从持久化 pending 队列向主服务投递显式偏好快照。"""

    def __init__(
        self,
        store: ProfileSyncStore,
        main_service: MainServiceClient,
        batch_size: int = _DEFAULT_BATCH_SIZE,
        poll_seconds: float = _DEFAULT_POLL_SECONDS,
    ) -> None:
        self._store = store
        self._main_service = main_service
        self._batch_size = max(1, batch_size)
        self._poll_seconds = max(0.1, poll_seconds)
        self._topics: tuple[TopicDefinition, ...] | None = None

    async def run_once(self) -> None:
        """处理一批新 pending；失败保留待办，供下轮或重启后继续。"""
        pending_items = await self._store.list_pending_profile_sync(self._batch_size)
        for pending in pending_items:
            event = await self._prepare_event(pending)
            if event is None:
                # 合法空结果或当前点已被新 revision 替换，均不应无限重试。
                continue
            try:
                await self._main_service.post_json("/bot/profile/events", _event_payload(event))
            except (httpx.HTTPError, MainServiceError) as exc:
                # 超时可能已经在主服务落库，仍保留同 eventId 供安全重试。
                logger.warning(
                    "显式画像同步失败，保留 pending，eventId=%s：%s", event.event_id, exc
                )
                continue
            cleared = await self._store.clear_profile_sync(
                event.event_id, event.memory_id, event.revision
            )
            if not cleared:
                # 期间若出现更新，revision 校验会拒绝清掉新待办。
                logger.info(
                    "显式画像同步成功但 pending 已更新，保留新 revision，eventId=%s",
                    event.event_id,
                )

    async def run_forever(self) -> None:
        """后台轮询；取消由 Runtime 收尾，依赖异常不终止服务。"""
        while True:
            try:
                await self.run_once()
            except asyncio.CancelledError:
                raise
            except Exception as exc:  # worker 边界留痕，pending 保留供下轮重试
                logger.warning("显式画像同步轮询异常（保留 pending）：%s", exc)
            await asyncio.sleep(self._poll_seconds)

    async def _prepare_event(self, pending: PendingProfileSync) -> ProfileSyncEvent | None:
        if pending.operation == "DELETE":
            return ProfileSyncEvent(
                event_id=pending.event_id,
                user_id=pending.user_id,
                memory_id=pending.memory_id,
                persona_version=pending.persona_version,
                revision=pending.revision,
                operation="DELETE",
            )

        topics = pending.topics
        effective_valence = pending.valence or infer_preference_valence(pending.content, None)
        if not pending.resolved:
            try:
                topics = extract_preferences(
                    pending.content,
                    effective_valence,
                    await self._load_topics(),
                )
            except (httpx.HTTPError, MainServiceError, ValueError) as exc:
                logger.warning("主服务主题词表不可用，保留 pending：%s", exc)
                return None
            if not topics:
                # 无受控主题是合法空结果；直接清理本次 pending，
                # 不向主服务发送空 UPSERT，也不让无关记忆永久重扫。
                await self._store.clear_profile_sync(
                    pending.event_id, pending.memory_id, pending.revision
                )
                return None
            if not await self._store.resolve_profile_sync_topics(
                pending.event_id,
                pending.memory_id,
                pending.revision,
                topics,
                effective_valence,
            ):
                return None

        return ProfileSyncEvent(
            event_id=pending.event_id,
            user_id=pending.user_id,
            memory_id=pending.memory_id,
            persona_version=pending.persona_version,
            revision=pending.revision,
            operation="UPSERT",
            topics=tuple(topics),
            valence=effective_valence,
        )

    async def _load_topics(self) -> tuple[TopicDefinition, ...]:
        if self._topics is not None:
            return self._topics
        raw_data = await self._main_service.get_json("/bot/profile/topics")
        raw_topics: object = raw_data
        if isinstance(raw_data, Mapping):
            raw_topics = raw_data.get("topics", raw_data.get("list", ()))
        if not isinstance(raw_topics, list):
            raise ValueError("主服务主题词表响应不是数组")

        parsed: list[TopicDefinition] = []
        seen_ids: set[str] = set()
        for raw_topic in raw_topics:
            if not isinstance(raw_topic, Mapping):
                raise ValueError("主服务主题词表包含非法项")
            topic_id = str(raw_topic.get("id", ""))
            label = str(raw_topic.get("label", ""))
            aliases_value = raw_topic.get("aliases", ())
            if not topic_id or not label or topic_id in seen_ids:
                raise ValueError("主服务主题词表 ID/label 重复或为空")
            if not isinstance(aliases_value, (list, tuple)):
                raise ValueError("主服务主题词表 aliases 不是数组")
            aliases = tuple(str(alias) for alias in aliases_value if str(alias))
            seen_ids.add(topic_id)
            parsed.append(TopicDefinition(id=topic_id, label=label, aliases=aliases))
        if not parsed or len(parsed) > 50:
            raise ValueError("主服务主题词表数量不在允许范围")
        self._topics = tuple(parsed)
        return self._topics


def _event_payload(event: ProfileSyncEvent) -> dict[str, object]:
    """显式构造 Java DTO 的 camelCase JSON，避免把本地字段名泄露成契约。"""
    return {
        "eventId": event.event_id,
        "userId": event.user_id,
        "memoryId": event.memory_id,
        "personaVersion": event.persona_version,
        "revision": event.revision,
        "operation": event.operation,
        "topics": list(event.topics),
        "valence": event.valence,
    }
