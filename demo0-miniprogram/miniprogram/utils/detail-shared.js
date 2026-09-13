"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.COMMENT_DELETE_CONFIRM_COLOR = exports.DETAIL_COMMENT_PAGE_SIZE = void 0;
exports.parseDetailContentId = parseDetailContentId;
exports.commentListFinished = commentListFinished;
exports.applyLikeResult = applyLikeResult;
exports.applyCollectResult = applyCollectResult;
exports.isRealNameRequiredMessage = isRealNameRequiredMessage;
exports.patchTopLevelComment = patchTopLevelComment;
exports.setCommentReplyLoading = setCommentReplyLoading;
exports.setCommentReplyError = setCommentReplyError;
exports.commentNeedsReplyFetch = commentNeedsReplyFetch;
exports.replyListPageSize = replyListPageSize;
exports.mergeCommentReplies = mergeCommentReplies;
exports.buildCommentActionSheetItems = buildCommentActionSheetItems;
exports.patchCommentInTree = patchCommentInTree;
exports.removeCommentFromTree = removeCommentFromTree;
exports.DETAIL_COMMENT_PAGE_SIZE = 10;
function parseDetailContentId(raw) {
    if (raw === undefined || raw === null || raw === '') {
        return null;
    }
    const n = Number(raw);
    if (!Number.isFinite(n) || n <= 0 || !Number.isInteger(n)) {
        return null;
    }
    return n;
}
function commentListFinished(page, fetchedCount) {
    if (page.hasMore === true) {
        return false;
    }
    if (page.hasMore === false) {
        return true;
    }
    const ps = page.pageSize ?? exports.DETAIL_COMMENT_PAGE_SIZE;
    return fetchedCount < ps;
}
function applyLikeResult(detail, vo) {
    const liked = vo.isLiked === true;
    const likeCount = vo.likedCount === undefined || vo.likedCount === null
        ? detail.likeCount
        : Math.max(0, Number(vo.likedCount) || 0);
    return { liked, likeCount };
}
function applyCollectResult(detail, vo) {
    const collected = vo.isCollect === true;
    const collectCount = vo.collectCount === undefined || vo.collectCount === null
        ? detail.collectCount
        : Math.max(0, Number(vo.collectCount) || 0);
    return { collected, collectCount };
}
function isRealNameRequiredMessage(msg) {
    return msg.includes('实名');
}
function patchTopLevelComment(list, parentId, patcher) {
    return list.map((row) => (row.commentId === parentId ? patcher(row) : row));
}
function setCommentReplyLoading(list, parentId, loading) {
    return patchTopLevelComment(list, parentId, (row) => ({
        ...row,
        replyLoading: loading,
        replyLoadError: loading ? false : row.replyLoadError,
    }));
}
function setCommentReplyError(list, parentId, errored) {
    return patchTopLevelComment(list, parentId, (row) => ({
        ...row,
        replyLoading: false,
        replyLoadError: errored,
    }));
}
/** 一级评论是否仍需调用 /comment/replyList（list 接口仅内联前 3 条） */
function commentNeedsReplyFetch(row) {
    if (!row) {
        return false;
    }
    if (row.replyLoadError) {
        return true;
    }
    const total = typeof row.replyCount === 'number' && Number.isFinite(row.replyCount) && row.replyCount > 0
        ? row.replyCount
        : 0;
    const loaded = Array.isArray(row.replies) ? row.replies.length : 0;
    return total > loaded;
}
function replyListPageSize(row) {
    const loaded = Array.isArray(row?.replies) ? row.replies.length : 0;
    const total = typeof row?.replyCount === 'number' && Number.isFinite(row.replyCount) && row.replyCount > 0
        ? row.replyCount
        : 0;
    if (total > 0) {
        return Math.max(total, loaded);
    }
    return Math.max(loaded + 10, 10);
}
function mergeCommentReplies(list, parentId, replies) {
    return patchTopLevelComment(list, parentId, (row) => ({
        ...row,
        replies,
        replyCount: Math.max(row.replyCount || 0, replies.length),
        replyLoading: false,
        replyLoadError: false,
    }));
}
/** 删除确认按钮色，对齐 `--color-danger` */
exports.COMMENT_DELETE_CONFIRM_COLOR = '#c43d3d';
function buildCommentActionSheetItems(currentUserId, commentUserId) {
    const isOwner = currentUserId !== null &&
        Number.isFinite(commentUserId) &&
        commentUserId > 0 &&
        commentUserId === currentUserId;
    return isOwner ? ['举报', '删除'] : ['举报'];
}
function patchCommentInTree(list, commentId, patcher) {
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
function removeCommentFromTree(list, commentId) {
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
