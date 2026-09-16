"""trigger 契约模型与 @ 检测行为测试（C-1/C-4 契约对齐版，2026-09-15）。"""

import pytest
from pydantic import ValidationError

from quanta_bot.pipeline.trigger import AI_NICKNAME, TriggerEvent, detect_mention

_MQ_MESSAGE = {
    "eventId": "evt-0001",
    "eventType": "BOT_MENTION_REQUESTED",
    "occurredAt": "2026-09-15T10:00:00Z",
    "retryCount": 0,
    "commentId": 101,
    "postId": 9,
    "answerId": None,
    "commenterUserId": 5,
    "commentContent": "@QuantaBot 帮我看看选课",
    "commentImages": [],
    "mentionedBot": True,
    "botTriggerKind": "mentioned",
    "parentId": None,
    "replyCommentId": None,
}


def test_trigger_event_from_mq_contract_json() -> None:
    """C-1 契约：MQ JSON（camelCase）直收为 TriggerEvent；契约外的多余字段忽略（eventType/occurredAt/retryCount）。"""
    ev = TriggerEvent.model_validate(_MQ_MESSAGE)
    assert ev.event_id == "evt-0001"
    assert ev.comment_id == 101
    assert ev.post_id == 9
    assert ev.answer_id is None
    assert ev.commenter_user_id == 5
    assert ev.content == "@QuantaBot 帮我看看选课"
    assert ev.images == ()
    assert ev.mentioned_bot is True
    assert ev.trigger_kind == "mentioned"


def test_trigger_event_replied_kind() -> None:
    """botTriggerKind=replied（直接回复 bot 评论）合法并携带父链定位。"""
    msg = {**_MQ_MESSAGE, "botTriggerKind": "replied", "replyCommentId": 88, "parentId": 10}
    ev = TriggerEvent.model_validate(msg)
    assert ev.trigger_kind == "replied"
    assert ev.reply_comment_id == 88
    assert ev.parent_id == 10


def test_trigger_event_rejects_missing_contract_field() -> None:
    """缺契约必填字段（eventId 等）→ 校验拒绝（Pydantic 即契约闸门——不臆造、不宽容）。"""
    with pytest.raises(ValidationError):
        TriggerEvent.model_validate(
            {
                "commentId": 1,
                "postId": 2,
                "commenterUserId": 3,
                "commentContent": "x",
                "mentionedBot": True,
            }
        )


def test_detect_mention_fallback_semantics() -> None:
    """C-4 兜底：文本 @ 检测保留（主判定已切结构化标记，此函数仅前端标记缺失时降级用）。"""
    assert detect_mention("@QuantaBot hi") is True
    assert detect_mention("@quantabot 在吗") is True
    assert detect_mention("随便聊聊") is False
    assert AI_NICKNAME == "QuantaBot"  # [待定项] bot 昵称定名后同步（Phase0 谈判 §5）
