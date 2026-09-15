"""pipeline/context —— 上下文组装（M2：接 CommentTreeFetcher 渲染最小文本）。

职责：拉帖子线程并渲染主楼/评论/父链/AI 标记的可读文本，作为生成层输入。
边界：不触碰网络（评论树客户端经端口注入）；筛选策略/长度预算/帖子间隔离强化 M3 落地
      （技术选型 §10.1 ③）；M1 的同步 stub 在本任务异步化。
"""

from quanta_bot.pipeline.ports import CommentTreeFetcher
from quanta_bot.pipeline.trigger import TriggerEvent


async def build_context(event: TriggerEvent, fetcher: CommentTreeFetcher) -> str:
    """拉取线程并渲染最小上下文文本（M3 起做筛选与长度预算）。"""
    thread = await fetcher.fetch(event.post_id)
    lines = [f"【主楼#{thread.post.post_id}】{thread.post.title}：{thread.post.content}"]
    for node in thread.comments:
        prefix = f"【评论#{node.comment_id}】"
        if node.parent_comment_id is not None:
            prefix += f"（回复#{node.parent_comment_id}）"
        if node.is_ai:
            prefix += "[AI]"
        lines.append(f"{prefix}{node.author_user_id}：{node.content}")
    return "\n".join(lines)
