import type { ContentDetailModel } from '../types/detail';
import type { CommentItemModel, CommentPageVO } from '../types/comment';
import type { LikeResultVO, CollectResultVO } from '../types/interaction';

export const DETAIL_COMMENT_PAGE_SIZE = 10;

export function parseDetailContentId(raw: string | undefined | null): number | null {
  if (raw === undefined || raw === null || raw === '') {
    return null;
  }
  const n = Number(raw);
  if (!Number.isFinite(n) || n <= 0 || !Number.isInteger(n)) {
    return null;
  }
  return n;
}

export function commentListFinished(page: CommentPageVO, fetchedCount: number): boolean {
  if (page.hasMore === true) {
    return false;
  }
  if (page.hasMore === false) {
    return true;
  }
  const ps = page.pageSize ?? DETAIL_COMMENT_PAGE_SIZE;
  return fetchedCount < ps;
}

export function applyLikeResult(detail: ContentDetailModel, vo: LikeResultVO): Partial<ContentDetailModel> {
  const liked = vo.isLiked === true;
  const likeCount =
    vo.likedCount === undefined || vo.likedCount === null
      ? detail.likeCount
      : Math.max(0, Number(vo.likedCount) || 0);
  return { liked, likeCount };
}

export function applyCollectResult(
  detail: ContentDetailModel,
  vo: CollectResultVO,
): Partial<ContentDetailModel> {
  const collected = vo.isCollect === true;
  const collectCount =
    vo.collectCount === undefined || vo.collectCount === null
      ? detail.collectCount
      : Math.max(0, Number(vo.collectCount) || 0);
  return { collected, collectCount };
}

export function isRealNameRequiredMessage(msg: string): boolean {
  return msg.includes('实名');
}

export function patchTopLevelComment(
  list: CommentItemModel[],
  parentId: number,
  patcher: (item: CommentItemModel) => CommentItemModel,
): CommentItemModel[] {
  return list.map((row) => (row.commentId === parentId ? patcher(row) : row));
}

export function setCommentReplyLoading(
  list: CommentItemModel[],
  parentId: number,
  loading: boolean,
): CommentItemModel[] {
  return patchTopLevelComment(list, parentId, (row) => ({
    ...row,
    replyLoading: loading,
    replyLoadError: loading ? false : row.replyLoadError,
  }));
}

export function setCommentReplyError(
  list: CommentItemModel[],
  parentId: number,
  errored: boolean,
): CommentItemModel[] {
  return patchTopLevelComment(list, parentId, (row) => ({
    ...row,
    replyLoading: false,
    replyLoadError: errored,
  }));
}

/** 一级评论是否仍需调用 /comment/replyList（list 接口仅内联前 3 条） */
export function commentNeedsReplyFetch(row: CommentItemModel | undefined | null): boolean {
  if (!row) {
    return false;
  }
  if (row.replyLoadError) {
    return true;
  }
  const total =
    typeof row.replyCount === 'number' && Number.isFinite(row.replyCount) && row.replyCount > 0
      ? row.replyCount
      : 0;
  const loaded = Array.isArray(row.replies) ? row.replies.length : 0;
  return total > loaded;
}

export function replyListPageSize(row: CommentItemModel | undefined | null): number {
  const loaded = Array.isArray(row?.replies) ? row.replies.length : 0;
  const total =
    typeof row?.replyCount === 'number' && Number.isFinite(row.replyCount) && row.replyCount > 0
      ? row.replyCount
      : 0;
  if (total > 0) {
    return Math.max(total, loaded);
  }
  return Math.max(loaded + 10, 10);
}

export function mergeCommentReplies(
  list: CommentItemModel[],
  parentId: number,
  replies: CommentItemModel[],
): CommentItemModel[] {
  return patchTopLevelComment(list, parentId, (row) => ({
    ...row,
    replies,
    replyCount: Math.max(row.replyCount || 0, replies.length),
    replyLoading: false,
    replyLoadError: false,
  }));
}

/** 删除确认按钮色，对齐 `--color-danger` */
export const COMMENT_DELETE_CONFIRM_COLOR = '#c43d3d';

export function buildCommentActionSheetItems(
  currentUserId: number | null,
  commentUserId: number,
): string[] {
  const isOwner =
    currentUserId !== null &&
    Number.isFinite(commentUserId) &&
    commentUserId > 0 &&
    commentUserId === currentUserId;
  return isOwner ? ['举报', '删除'] : ['举报'];
}

export function patchCommentInTree(
  list: CommentItemModel[],
  commentId: number,
  patcher: (item: CommentItemModel) => CommentItemModel,
): CommentItemModel[] {
  return list.map((row) => {
    if (row.commentId === commentId) {
      return patcher(row);
    }
    if (!row.replies?.length) {
      return row;
    }
    let touched = false;
    const nextReplies = row.replies.map((reply) => {
      if (reply.commentId === commentId) {
        touched = true;
        return patcher(reply);
      }
      return reply;
    });
    return touched ? { ...row, replies: nextReplies } : row;
  });
}

export function findCommentInTree(
  list: CommentItemModel[],
  commentId: number,
): CommentItemModel | null {
  for (const row of list) {
    if (row.commentId === commentId) {
      return row;
    }
    const reply = row.replies?.find((item) => item.commentId === commentId);
    if (reply) {
      return reply;
    }
  }
  return null;
}

export type CommentRemoveDepth = 0 | 1 | null;

export function removeCommentFromTree(
  list: CommentItemModel[],
  commentId: number,
): { list: CommentItemModel[]; depth: CommentRemoveDepth } {
  if (list.some((c) => c.commentId === commentId)) {
    return { list: list.filter((c) => c.commentId !== commentId), depth: 0 };
  }
  let removed = false;
  const next = list.map((row) => {
    if (!row.replies?.length) {
      return row;
    }
    if (!row.replies.some((r) => r.commentId === commentId)) {
      return row;
    }
    removed = true;
    const replies = row.replies.filter((r) => r.commentId !== commentId);
    const replyCount = Math.max(0, (row.replyCount ?? replies.length) - 1);
    return { ...row, replies, replyCount };
  });
  return { list: removed ? next : list, depth: removed ? 1 : null };
}
