"""pipeline/context —— 上下文组装（M2：接 CommentTreeFetcher 端口渲染父链+bot 历史）。

职责：拉帖子线程并渲染主楼/父链/AI 历史的可读文本，作为生成层输入。
边界：不触碰网络（评论树客户端经端口注入）；筛选策略/长度预算/帖子隔离强化 M3 落地；
      tree 分页接口（C-2②）M3 筛选策略时再接。
"""

from quanta_bot.pipeline.ports import CommentTreeFetcher, PostThread
from quanta_bot.pipeline.trigger import TriggerEvent


def _render(thread: PostThread) -> str:
    lines = [f"【主楼#{thread.post.post_id}】{thread.post.title}：{thread.post.content}"]
    for node in thread.chain:
        ai_tag = "[AI]" if node.is_ai else ""
        lines.append(f"【评论#{node.comment_id}】{ai_tag}{node.user_id}：{node.content}")
    for node in thread.bot_history:
        lines.append(f"【AI历史#{node.comment_id}】{node.content}")
    return "\n".join(lines)


async def build_context(event: TriggerEvent, fetcher: CommentTreeFetcher) -> str:
    """拉取线程并渲染上下文文本（M3 起做筛选与长度预算）。"""
    return _render(await fetcher.fetch_context(event))
