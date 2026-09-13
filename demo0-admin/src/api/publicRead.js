import request from './request'

/** C 端只读接口（管理端 JWT 可访问，用于举报/详情补全） */
export const publicReadApi = {
  getContentDetail: (contentId) => request.get(`/content/detail/${contentId}`),

  listComments: (params) => request.get('/comment/list', { params }),

  /**
   * 在评论列表中查找指定评论（需 contentId）
   */
  async findCommentInList(commentId, contentId) {
    if (!commentId || !contentId) return null
    const page = await this.listComments({
      contentId,
      pageNum: 1,
      pageSize: 100,
      sortType: 1,
    })
    const items = flattenCommentList(page?.list)
    return items.find((c) => Number(c.commentId) === Number(commentId)) ?? null
  },

  /**
   * 通过管理端评论分页扫描定位评论（无 contentId 时的兜底）
   */
  async findCommentViaAdminPage(commentId) {
    if (!commentId) return null
    let pageNum = 1
    const pageSize = 50
    while (true) {
      const data = await request.get('/admin/comment/page', { params: { pageNum, pageSize } })
      const records = Array.isArray(data?.records) ? data.records : []
      const hit = records.find((c) => Number(c.commentId) === Number(commentId))
      if (hit) return hit
      const total = Number(data?.total ?? 0)
      if (records.length === 0 || pageNum * pageSize >= total) break
      pageNum += 1
    }
    return null
  },

  /**
   * 解析评论详情并尽量补全所属帖子上下文
   */
  async resolveCommentDetail(row) {
    const commentId = row?.commentId
    if (!commentId) return buildCommentSnapshotFromReport(row)

    let comment =
      (row.contentId && (await this.findCommentInList(commentId, row.contentId))) ||
      (await this.findCommentViaAdminPage(commentId))

    if (!comment) {
      return buildCommentSnapshotFromReport(row)
    }

    const contentId = comment.contentId ?? comment.content_id
    let post = null
    if (contentId) {
      try {
        post = await this.getContentDetail(contentId)
      } catch {
        post = null
      }
    }

    return {
      commentId: comment.commentId ?? comment.comment_id ?? commentId,
      content: comment.content ?? row.targetCommentPreview ?? '',
      userId: comment.userId ?? comment.user_id,
      contentId,
      answerId: comment.answerId ?? comment.answer_id,
      createTime: comment.createTime ?? comment.create_time,
      postTitle: post?.title ?? row.targetPostTitle ?? '',
      postContent: post?.content ?? '',
      postPublishUserId: post?.publishUserId,
    }
  },
}

function flattenCommentList(list) {
  if (!Array.isArray(list)) return []
  const out = []
  for (const item of list) {
    if (!item || typeof item !== 'object') continue
    out.push(item)
    const replies = item.replies ?? item.replyList
    if (Array.isArray(replies)) {
      for (const reply of replies) {
        if (reply && typeof reply === 'object') out.push(reply)
      }
    }
  }
  return out
}

export function buildCommentSnapshotFromReport(row) {
  if (!row) return null
  return {
    commentId: row.commentId,
    content: row.targetCommentPreview || '',
    postTitle: row.targetPostTitle || '',
    userId: row.targetUserId,
    contentId: row.contentId,
  }
}

export default publicReadApi
