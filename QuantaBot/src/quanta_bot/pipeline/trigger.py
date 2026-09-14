"""pipeline/trigger —— 触发检测（被动 @ 检测）与触发事件契约。

职责：判定评论是否 @ 了 AI 账号；定义 TriggerEvent（管线入口数据）。
边界：不做幂等（crosscutting/idempotency 负责）；不解析楼层结构（context 负责）。
[Phase 0 对齐点] 事件字段（comment_id/post_id/author_user_id/content）为 Agent 内部最小集，
主服务 MQ 事件契约（P0-1）敲定后在此对齐，未定字段不臆造。
"""

from pydantic import BaseModel, Field

# AI 账号昵称（M1 写死占位；M3 人格文件落地后改为从配置/人格层读取）
AI_NICKNAME = "QuantaBot"


class TriggerEvent(BaseModel):
    """触发事件（[Phase 0 对齐点]：字段随主服务评论事件契约对齐）。"""

    comment_id: int = Field(description="主服务评论 ID——幂等唯一键（缺口3）")
    post_id: int = Field(description="帖子 ID——同帖串行与上下文锚点")
    author_user_id: int = Field(description="触发评论作者")
    content: str = Field(description="评论原文（含 @ 文本）")


def detect_mention(content: str, nickname: str = AI_NICKNAME) -> bool:
    """检测评论是否 @ 了 AI 账号（M1：大小写不敏感子串匹配；语义级检测 M3）。"""
    return f"@{nickname}".lower() in content.lower()
