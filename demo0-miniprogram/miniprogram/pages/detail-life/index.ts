import { CONTENT_TYPE_LIFE } from '../../constants/content';
import {
  deleteContent,
  getContentDetail,
  mapContentVOToDetail,
  reportContent,
} from '../../services/content.service';
import {
  deleteComment,
  getCommentList,
  getReplyList,
  mapCommentPageToItems,
  reportComment,
  sendComment,
  likeComment,
} from '../../services/comment.service';
import { setContentLike, setContentCollect } from '../../services/interaction.service';
import type { ApiErrorType } from '../../types/api';
import type { CommentItemModel } from '../../types/comment';
import type { ContentDetailModel } from '../../types/detail';
import { getUserInfo } from '../../services/user.service';
import { ensureRealNameVerified } from '../../services/real-name.service';
import {
  applyCollectResult,
  applyLikeResult,
  buildCommentActionSheetItems,
  commentListFinished,
  COMMENT_DELETE_CONFIRM_COLOR,
  DETAIL_COMMENT_PAGE_SIZE,
  isRealNameRequiredMessage,
  commentNeedsReplyFetch,
  mergeCommentReplies,
  parseDetailContentId,
  patchCommentInTree,
  findCommentInTree,
  removeCommentFromTree,
  replyListPageSize,
  setCommentReplyError,
  setCommentReplyLoading,
} from '../../utils/detail-shared';
import { feedFullScreenError } from '../../utils/feed-error';
import type { RequestFailure } from '../../utils/request';
import { pickReportType } from '../../utils/report-flow';
import { ROUTES } from '../../constants/route';
import {
  createPageRefreshSnapshot,
  handleDetailProfileRefreshOnShow,
  type PageRefreshSnapshot,
} from '../../utils/page-refresh';
import { navigateToUserProfileFromEvent } from '../../utils/user-profile-nav';

type PageData = {
  contentIdNum: number | null;
  detail: ContentDetailModel | null;
  typeMismatch: boolean;
  loading: boolean;
  refreshing: boolean;
  errorType: ApiErrorType | '';
  errorMessage: string;
  stateTitle: string;
  stateActionText: string;
  invalidId: boolean;
  likeLoading: boolean;
  collectLoading: boolean;
  comments: CommentItemModel[];
  commentLoading: boolean;
  commentLoadingMore: boolean;
  commentLoadMoreError: boolean;
  commentFinished: boolean;
  commentPageNum: number;
  composerValue: string;
  composerSubmitting: boolean;
  replyPlaceholder: string;
  composerVisible: boolean;
  composerAutoFocus: boolean;
  replyTargetNick: string;
  contentDeleted: boolean;
  currentUserId: number | null;
};

function detailErrorState(res: RequestFailure): {
  errorType: ApiErrorType;
  stateTitle: string;
  errorMessage: string;
  stateActionText: string;
} {
  if (res.errorType === 'server' && isRealNameRequiredMessage(res.message)) {
    return {
      errorType: 'server',
      stateTitle: '需要完成实名认证',
      errorMessage: res.message || '请先完成实名认证后再查看内容',
      stateActionText: '重新加载',
    };
  }
  const f = feedFullScreenError(res);
  return {
    errorType: f.errorType,
    stateTitle: f.stateTitle,
    errorMessage: f.errorMessage,
    stateActionText: f.stateActionText,
  };
}

Page({
  data: {
    contentIdNum: null,
    detail: null,
    typeMismatch: false,
    loading: true,
    refreshing: false,
    errorType: '',
    errorMessage: '',
    stateTitle: '',
    stateActionText: '',
    invalidId: false,
    likeLoading: false,
    collectLoading: false,
    comments: [],
    commentLoading: false,
    commentLoadingMore: false,
    commentLoadMoreError: false,
    commentFinished: true,
    commentPageNum: 1,
    composerValue: '',
    composerSubmitting: false,
    replyPlaceholder: '友善评论，文明发言',
    composerVisible: false,
    composerAutoFocus: false,
    replyTargetNick: '',
    contentDeleted: false,
    currentUserId: null,
  } as PageData,

  _commentLock: false,
  _replyTarget: null as
    | {
        parentId: number;
        replyCommentId: number;
        replyUserId: number;
      }
    | null,
  _refreshSnapshot: createPageRefreshSnapshot() as PageRefreshSnapshot,

  onLoad(query: Record<string, string | undefined>) {
    const id = parseDetailContentId(query.contentId);
    if (id === null) {
      this.setData({
        invalidId: true,
        loading: false,
        contentIdNum: null,
        errorType: '',
      });
      return;
    }
    this.setData({ contentIdNum: id, invalidId: false, contentDeleted: false });
    void this.syncCurrentUser();
    void this.loadDetail(false);
  },

  onShow() {
    handleDetailProfileRefreshOnShow(this, () => this.applyProfileRefresh());
  },

  async applyProfileRefresh() {
    await this.syncCurrentUser();
    const uid = this.data.currentUserId;
    const authorId = this.data.detail?.authorId;
    if (
      this.data.detail &&
      !this.data.typeMismatch &&
      uid !== null &&
      authorId !== undefined &&
      authorId === uid
    ) {
      void this.loadDetail(false);
    }
    if (this.data.detail && !this.data.typeMismatch && !this.data.invalidId) {
      void this.loadComments(true);
    }
  },

  async syncCurrentUser() {
    const res = await getUserInfo();
    if (!res.ok) {
      return;
    }
    const uidRaw = res.data.userId;
    const uid = uidRaw === undefined || uidRaw === null ? NaN : Number(uidRaw);
    const currentUserId = Number.isFinite(uid) && uid > 0 ? uid : null;
    this.setData({ currentUserId });
  },

  onPullDownRefresh() {
    if (this.data.invalidId || !this.data.contentIdNum) {
      wx.stopPullDownRefresh();
      return;
    }
    void this.refreshAll();
  },

  onReachBottom() {
    if (
      this.data.typeMismatch ||
      !this.data.detail ||
      this.data.detail.contentType !== CONTENT_TYPE_LIFE ||
      this.data.commentLoading ||
      this.data.commentLoadingMore ||
      this.data.commentFinished ||
      this.data.commentLoadMoreError
    ) {
      return;
    }
    void this.loadComments(false);
  },

  onDetailStateAction() {
    if (this.data.invalidId) {
      wx.navigateBack({ fail: () => {} });
      return;
    }
    void this.loadDetail(false);
  },

  onTypeMismatchAction() {
    wx.navigateBack({ fail: () => {} });
  },

  onPreviewImage(e: WechatMiniprogram.TouchEvent) {
    const url = e.currentTarget?.dataset?.url;
    const urls = this.data.detail?.images;
    if (typeof url !== 'string' || !Array.isArray(urls) || urls.length === 0) {
      return;
    }
    wx.previewImage({ current: url, urls });
  },

  onAuthorTap(e: WechatMiniprogram.CustomEvent<{ userId?: number }>) {
    navigateToUserProfileFromEvent(e);
  },

  onContentMore() {
    const cid = this.data.contentIdNum;
    if (!cid || !this.data.detail || this.data.typeMismatch) {
      return;
    }
    wx.showActionSheet({
      itemList: ['举报内容', '删除内容'],
      success: (sheet) => {
        if (sheet.tapIndex === 0) {
          void this.runContentReport(cid);
        } else if (sheet.tapIndex === 1) {
          this.confirmDeleteContent(cid);
        }
      },
    });
  },

  async runContentReport(contentId: number) {
    const rt = await pickReportType();
    if (rt === null) {
      return;
    }
    const res = await reportContent({ contentId, reportType: rt });
    if (!res.ok) {
      wx.showToast({ title: res.message.slice(0, 18) || '提交失败', icon: 'none' });
      return;
    }
    wx.showToast({ title: '已提交', icon: 'success' });
  },

  confirmDeleteContent(contentId: number) {
    wx.showModal({
      title: '删除内容',
      content: '删除后不可恢复，确定删除该内容吗？',
      confirmText: '删除',
      confirmColor: '#e54d42',
      success: (modal) => {
        if (!modal.confirm) {
          return;
        }
        void (async () => {
          const res = await deleteContent(contentId);
          if (!res.ok) {
            wx.showToast({ title: res.message.slice(0, 18) || '删除失败', icon: 'none' });
            return;
          }
          wx.showToast({ title: '已删除', icon: 'success' });
          this.setData({
            detail: null,
            comments: [],
            commentFinished: true,
            commentLoading: false,
          });
          wx.navigateBack({
            fail: () => {
              this.setData({ contentDeleted: true });
            },
          });
        })();
      },
    });
  },

  onContentDeletedBack() {
    wx.switchTab({ url: ROUTES.HOME });
  },

  onCommentMore(e: WechatMiniprogram.CustomEvent<{ commentId?: number; userId?: number }>) {
    const id = e.detail?.commentId;
    const userId = Number(e.detail?.userId);
    if (!Number.isFinite(id) || !id) {
      return;
    }
    const itemList = buildCommentActionSheetItems(this.data.currentUserId, userId);
    wx.showActionSheet({
      itemList,
      success: (sheet) => {
        const picked = itemList[sheet.tapIndex];
        if (picked === '举报') {
          void this.runCommentReport(id);
        } else if (picked === '删除') {
          this.confirmDeleteComment(id);
        }
      },
    });
  },

  async runCommentReport(commentId: number) {
    const rt = await pickReportType();
    if (rt === null) {
      return;
    }
    const res = await reportComment({ commentId, reportType: rt });
    if (!res.ok) {
      wx.showToast({ title: res.message.slice(0, 18) || '提交失败', icon: 'none' });
      return;
    }
    wx.showToast({ title: '已提交', icon: 'success' });
  },

  confirmDeleteComment(commentId: number) {
    wx.showModal({
      title: '删除评论',
      content: '删除后不可恢复，确定删除该评论吗？',
      confirmText: '删除',
      confirmColor: COMMENT_DELETE_CONFIRM_COLOR,
      success: (modal) => {
        if (!modal.confirm) {
          return;
        }
        void (async () => {
          const res = await deleteComment(commentId);
          if (!res.ok) {
            wx.showToast({ title: res.message.slice(0, 18) || '删除失败', icon: 'none' });
            return;
          }
          wx.showToast({ title: '已删除', icon: 'success' });
          const { list, depth } = removeCommentFromTree(this.data.comments, commentId);
          if (depth === null) {
            return;
          }
          const d = this.data.detail;
          const patch: Partial<PageData> = { comments: list };
          if (depth === 0 && d) {
            patch.detail = { ...d, commentCount: Math.max(0, d.commentCount - 1) };
          }
          this.setData(patch);
        })();
      },
    });
  },

  async refreshAll() {
    this.setData({ refreshing: true });
    try {
      await this.loadDetail(true);
      if (this.data.detail && this.data.detail.contentType === CONTENT_TYPE_LIFE && !this.data.typeMismatch) {
        await this.runCommentsReset(true);
      }
    } catch {
      wx.showToast({ title: '刷新失败', icon: 'none' });
    } finally {
      this.setData({ refreshing: false });
      wx.stopPullDownRefresh();
    }
  },

  runCommentsReset(silent: boolean): Promise<void> {
    const cid = this.data.contentIdNum;
    if (!cid || this.data.typeMismatch) {
      return Promise.resolve();
    }
    if (!silent) {
      this.setData({
        commentPageNum: 1,
        comments: [],
        commentFinished: false,
        commentLoadMoreError: false,
        commentLoading: true,
      });
    } else {
      this.setData({
        commentPageNum: 1,
        commentLoading: true,
        commentLoadMoreError: false,
      });
    }
    return getCommentList({
      contentId: cid,
      pageNum: 1,
      pageSize: DETAIL_COMMENT_PAGE_SIZE,
    }).then((res) => {
      if (!res.ok) {
        if (!silent) {
          wx.showToast({ title: res.message.slice(0, 14) || '评论加载失败', icon: 'none' });
          this.setData({ commentLoading: false, comments: [], commentFinished: true });
        } else {
          this.setData({ commentLoading: false });
        }
        return;
      }
      const items = mapCommentPageToItems(res.data);
      const finished = commentListFinished(res.data, items.length);
      this.setData({
        comments: items,
        commentLoading: false,
        commentFinished: finished,
        commentPageNum: 1,
      });
    });
  },

  async loadDetail(isRefresh: boolean) {
    const cid = this.data.contentIdNum;
    if (!cid) {
      return;
    }
    if (!isRefresh) {
      this.setData({
        loading: true,
        errorType: '',
        errorMessage: '',
        stateTitle: '',
        stateActionText: '',
        typeMismatch: false,
      });
    }
    const res = await getContentDetail(cid);
    if (!res.ok) {
      if (isRefresh && this.data.detail) {
        wx.showToast({ title: res.message.slice(0, 14) || '加载失败', icon: 'none' });
        this.setData({ loading: false });
        return;
      }
      const err = detailErrorState(res);
      this.setData({
        loading: false,
        detail: null,
        typeMismatch: false,
        errorType: err.errorType,
        errorMessage: err.errorMessage,
        stateTitle: err.stateTitle,
        stateActionText: err.stateActionText,
      });
      return;
    }
    const mapped = mapContentVOToDetail(res.data);
    if (!mapped) {
      const fake: RequestFailure = { ok: false, errorType: 'invalidData', message: '内容数据异常' };
      const err = detailErrorState(fake);
      this.setData({
        loading: false,
        detail: null,
        errorType: err.errorType,
        stateTitle: err.stateTitle,
        errorMessage: err.errorMessage,
        stateActionText: err.stateActionText,
      });
      return;
    }
    if (mapped.contentType !== CONTENT_TYPE_LIFE) {
      this.setData({
        loading: false,
        detail: mapped,
        typeMismatch: true,
        errorType: '',
      });
      return;
    }
    this.setData({
      loading: false,
      detail: mapped,
      typeMismatch: false,
      errorType: '',
    });
    if (!isRefresh) {
      await this.runCommentsReset(false);
    }
  },

  async loadComments(reset: boolean) {
    const cid = this.data.contentIdNum;
    if (!cid || this.data.typeMismatch || this._commentLock) {
      return;
    }
    if (reset) {
      await this.runCommentsReset(false);
      return;
    }
    if (this.data.commentFinished || this.data.commentLoadingMore) {
      return;
    }
    this._commentLock = true;
    const nextPage = this.data.commentPageNum + 1;
    this.setData({ commentLoadingMore: true, commentLoadMoreError: false });
    const res = await getCommentList({
      contentId: cid,
      pageNum: nextPage,
      pageSize: DETAIL_COMMENT_PAGE_SIZE,
    });
    this._commentLock = false;
    if (!res.ok) {
      this.setData({ commentLoadingMore: false, commentLoadMoreError: true });
      return;
    }
    const batch = mapCommentPageToItems(res.data);
    const merged = this.data.comments.concat(batch);
    const finished = commentListFinished(res.data, batch.length);
    this.setData({
      comments: merged,
      commentPageNum: nextPage,
      commentLoadingMore: false,
      commentFinished: finished || batch.length === 0,
      commentLoadMoreError: false,
    });
  },

  onComposerInput(e: WechatMiniprogram.CustomEvent<{ value?: string }>) {
    this.setData({ composerValue: e.detail?.value ?? '' });
  },

  async onComposerSubmit(e: WechatMiniprogram.CustomEvent<{ content?: string }>) {
    const cid = this.data.contentIdNum;
    const text = (e.detail?.content ?? this.data.composerValue).trim();
    if (!cid || !text || this.data.composerSubmitting) {
      return;
    }
    const verified = await ensureRealNameVerified({
      reason: '发表评论',
      description: '发表评论需先完成校友实名认证，审核通过后即可互动。',
    });
    if (!verified) {
      return;
    }
    this.setData({ composerSubmitting: true });
    const payload: {
      contentId: number;
      content: string;
      parentId?: number;
      replyCommentId?: number;
      replyUserId?: number;
    } = { contentId: cid, content: text };
    if (this._replyTarget) {
      payload.parentId = this._replyTarget.parentId;
      payload.replyCommentId = this._replyTarget.replyCommentId;
      payload.replyUserId = this._replyTarget.replyUserId;
    }
    const res = await sendComment(payload);
    this.setData({ composerSubmitting: false });
    if (!res.ok) {
      wx.showToast({ title: res.message.slice(0, 14) || '发送失败', icon: 'none' });
      return;
    }
    this._replyTarget = null;
    this.setData({
      composerValue: '',
      replyPlaceholder: '友善评论，文明发言',
      replyTargetNick: '',
      composerVisible: false,
      composerAutoFocus: false,
    });
    wx.showToast({ title: '评论已提交审核', icon: 'success' });
  },

  async onDetailLike() {
    const cid = this.data.contentIdNum;
    const d = this.data.detail;
    if (!cid || !d || this.data.likeLoading || this.data.collectLoading) {
      return;
    }
    this.setData({ likeLoading: true });
    const res = await setContentLike(cid, d.liked !== true);
    this.setData({ likeLoading: false });
    if (!res.ok) {
      wx.showToast({ title: res.message.slice(0, 14) || '操作失败', icon: 'none' });
      return;
    }
    const patch = applyLikeResult(d, res.data);
    this.setData({ detail: { ...d, ...patch } });
  },

  async onDetailCollect() {
    const cid = this.data.contentIdNum;
    const d = this.data.detail;
    if (!cid || !d || this.data.likeLoading || this.data.collectLoading) {
      return;
    }
    this.setData({ collectLoading: true });
    const res = await setContentCollect(cid, d.collected !== true);
    this.setData({ collectLoading: false });
    if (!res.ok) {
      wx.showToast({ title: res.message.slice(0, 14) || '操作失败', icon: 'none' });
      return;
    }
    const patch = applyCollectResult(d, res.data);
    this.setData({ detail: { ...d, ...patch } });
  },

  openComposer() {
    this.setData({ composerVisible: true, composerAutoFocus: false }, () => {
      this.setData({ composerAutoFocus: true });
    });
  },

  closeComposer() {
    this.setData({ composerVisible: false, composerAutoFocus: false });
  },

  onComposerSheetPanelTap() {},

  onCancelReply() {
    this._replyTarget = null;
    this.setData({
      replyPlaceholder: '友善评论，文明发言',
      replyTargetNick: '',
    });
  },

  onCommentRetry() {
    void this.loadComments(false);
  },

  onCommentReply(e: WechatMiniprogram.CustomEvent<{ commentId?: number; userId?: number; nickName?: string }>) {
    const commentId = e.detail?.commentId;
    const userId = e.detail?.userId;
    const nickName = e.detail?.nickName || '';
    if (!Number.isFinite(commentId) || !commentId || !Number.isFinite(userId) || !userId) {
      return;
    }
    const topLevel = this.findTopLevelParent(commentId);
    const parentId = topLevel?.commentId ?? commentId;
    this._replyTarget = {
      parentId,
      replyCommentId: commentId,
      replyUserId: userId,
    };
    const nick = nickName || '用户';
    this.setData({
      replyPlaceholder: `回复 ${nick}：`,
      replyTargetNick: nick,
    });
    this.openComposer();
  },

  async onCommentLoadReplies(e: WechatMiniprogram.CustomEvent<{ commentId?: number }>) {
    const parentId = e.detail?.commentId;
    const cid = this.data.contentIdNum;
    if (!Number.isFinite(parentId) || !parentId || !cid) {
      return;
    }
    const top = this.data.comments.find((row) => row.commentId === parentId);
    if (top?.replyLoading) {
      return;
    }
    if (!commentNeedsReplyFetch(top)) {
      return;
    }
    let comments = setCommentReplyLoading(this.data.comments, parentId, true);
    this.setData({ comments });
    const res = await getReplyList({
      parentCommentId: parentId,
      contentId: cid,
      pageNum: 1,
      pageSize: replyListPageSize(top),
    });
    if (!res.ok) {
      comments = setCommentReplyError(comments, parentId, true);
      this.setData({ comments });
      return;
    }
    const list = mapCommentPageToItems(res.data);
    comments = mergeCommentReplies(comments, parentId, list);
    this.setData({ comments });
  },

  async onCommentLike(e: WechatMiniprogram.CustomEvent<{ commentId?: number }>) {
    const id = e.detail?.commentId;
    if (!Number.isFinite(id) || !id) {
      return;
    }
    const current = findCommentInTree(this.data.comments, id);
    if (!current) {
      return;
    }
    const res = await likeComment(id, current.liked !== true);
    if (!res.ok) {
      wx.showToast({ title: res.message.slice(0, 14) || '操作失败', icon: 'none' });
      return;
    }
    const liked = res.data.isLiked === true;
    const likeCount =
      res.data.likedCount === undefined || res.data.likedCount === null
        ? undefined
        : Math.max(0, Number(res.data.likedCount) || 0);
    this.setData({
      comments: patchCommentInTree(this.data.comments, id, (c) => ({
        ...c,
        liked,
        likeCount: likeCount === undefined ? c.likeCount : likeCount,
      })),
    });
  },

  findTopLevelParent(commentId: number): CommentItemModel | null {
    for (const row of this.data.comments) {
      if (row.commentId === commentId) {
        return row;
      }
      const replies = row.replies || [];
      if (replies.some((it) => it.commentId === commentId)) {
        return row;
      }
    }
    return null;
  },
});
