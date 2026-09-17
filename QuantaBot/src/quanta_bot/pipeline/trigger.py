"""pipeline/trigger —— 触发事件契约与 @ 检测。

职责：定义 TriggerEvent（对齐 demo0 BotMentionMessage 契约 C-1，2026-09-15 敲定；
      camelCase alias 直收 MQ JSON——Pydantic 校验即契约闸门）；
      detect_mention 文本兜底（C-4：主判定=事件结构化 mentionedBot 标记，前端标记缺失时降级）。
边界：不做幂等（crosscutting 负责，幂等键=comment_id，eventId 仅投递层辅助）；
      不解析楼层（评论树接口负责）。
[待定项] AI_NICKNAME 随 bot 昵称定名同步（Phase0 谈判 §5：量量/波仔/路路）。
"""

from typing import Literal

from pydantic import BaseModel, ConfigDict, Field

AI_NICKNAME = "QuantaBot"


class TriggerEvent(BaseModel):
    """触发事件（契约=demo0 Outbox BotMentionMessage，C-1）。"""

    model_config = ConfigDict(populate_by_name=True)  # 模型配置

    event_id: str = Field(
        alias="eventId", description="Outbox 唯一 ID——投递层辅助（幂等键仍是 comment_id）"
    )
    comment_id: int = Field(alias="commentId", description="主服务评论 ID——幂等唯一键（缺口3）")
    post_id: int = Field(alias="postId", description="帖子 ID（=contentId）")
    answer_id: int | None = Field(
        default=None, alias="answerId", description="专业区回答 ID（可空）"
    )

    commenter_user_id: int = Field(alias="commenterUserId", description="触发评论作者")
    content: str = Field(alias="commentContent", description="评论全文（≤500 字）")
    images: tuple[str, ...] = Field(
        default=(), alias="commentImages", description="评论图片（可空）"
    )
    mentioned_bot: bool = Field(
        alias="mentionedBot", description="demo0 结构化 @ 标记（主判定，C-4）"
    )
    trigger_kind: Literal["mentioned", "replied"] = Field(
        default="mentioned",
        alias="botTriggerKind",
        description="mentioned=卡片 @；replied=直接回复 bot 评论",
    )
    parent_id: int | None = Field(
        default=None, alias="parentId", description="触发评论父级（楼内定位辅助）"
    )
    reply_comment_id: int | None = Field(
        default=None, alias="replyCommentId", description="触发评论的被回复对象"
    )


def detect_mention(content: str, nickname: str = AI_NICKNAME) -> bool:
    """文本兜底 @ 检测（C-4 降级路径：前端结构化标记缺失时；主判定是 mentioned_bot 字段）。"""
    return f"@{nickname}".lower() in content.lower()
