import {
  acceptAnswer,
  getAnswerDetail,
  getAnswerList,
  likeAnswer,
  mapAnswerVOToModel,
} from '../../services/answer.service';
import { getContentDetail, mapContentVOToDetail } from '../../services/content.service';
import {
  deleteComment,
  getCommentList,
  getReplyList,
  likeComment,
  mapCommentPageToItems,
  reportComment,
  sendComment,
} from '../../services/comment.service';
import type { ApiErrorType } from '../../types/api';
import type { CommentItemModel } from '../../types/comment';
import type { AnswerModel, ContentDetailModel } from '../../types/detail';
import { getUserInfo } from '../../services/user.service';
import {
  createPageRefreshSnapshot,
  markRefreshConsumed,
  shouldReloadOnShow,
  type PageRefreshSnapshot,
} from '../../utils/page-refresh';
import {
  buildCommentActionSheetItems,
  commentListFinished,
  commentNeedsReplyFetch,
  COMMENT_DELETE_CONFIRM_COLOR,
  DETAIL_COMMENT_PAGE_SIZE,
  findCommentInTree,
  mergeCommentReplies,
  parseDetailContentId,
  patchCommentInTree,
  removeCommentFromTree,
  replyListPageSize,
  setCommentReplyError,
  setCommentReplyLoading,
} from '../../utils/detail-shared';
import { feedFullScreenError } from '../../utils/feed-error';
import type { RequestFailure } from '../../utils/request';
import { pickReportType } from '../../utils/report-flow';
import { navigateToUserProfileFromEvent } from '../../utils/user-profile-nav';
import { ROUTES } from '../../constants/route';

type ReplyTarget = {
  parentId: number;
  replyCommentId: number;
  replyUserId: number;
} | null;

type PageData = {
  answerIdNum: number | null;
  questionIdNum: number | null;
  invalidParams: boolean;
  loading: boolean;
  errorType: ApiErrorType | '';
  stateTitle: string;
  errorMessage: string;
  stateActionText: string;
  refreshing: boolean;
  questionDetail: ContentDetailModel | null;
  pinnedAnswer: AnswerModel | null;
  otherAnswers: AnswerModel[];
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
  answerCommentCount: number;
  answerLikeLoading: boolean;
  currentUserId: number | null;
  isQuestionAuthor: boolean;
  acceptingAnswerId: number | null;
};

function detailErrorState(res: RequestFailure): {
  errorType: ApiErrorType;
  stateTitle: string;
  errorMessage: string;
  stateActionText: string;
} {
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
    answerIdNum: null,
    questionIdNum: null,
    invalidParams: false,
    loading: true,
    errorType: '',
    stateTitle: '',
    errorMessage: '',
    stateActionText: '',
    refreshing: false,
    questionDetail: null,
    pinnedAnswer: null,
    otherAnswers: [],
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
    answerCommentCount: 0,
    answerLikeLoading: false,
    currentUserId: null,
    isQuestionAuthor: false,
    acceptingAnswerId: null,
  } as PageData,

  _commentLock: false,
  _replyTarget: null as ReplyTarget,
  _pageHasShown: false,
  _refreshSnapshot: createPageRefreshSnapshot() as PageRefreshSnapshot,
  _pendingFocusComment: false,

  onLoad(query: Record<string, string | undefined>) {
    const answerId = parseDetailContentId(query.answerId);
    const questionId = parseDetailContentId(query.questionId);
    if (answerId === null || questionId === null) {
      this.setData({ invalidParams: true, loading: false });
      return;
    }
    this._pendingFocusComment = query.focusComment === '1';
    this.setData({
      answerIdNum: answerId,
      questionIdNum: questionId,
      invalidParams: false,
      loading: true,
    });
    void this.syncQuestionAuthor();
    void this.loadPage(false);
  },

  onShow() {
    if (!this._pageHasShown) {
      this._pageHasShown = true;
      return;
    }
    if (shouldReloadOnShow(this._refreshSnapshot, ['userProfile'])) {
      markRefreshConsumed(this._refreshSnapshot, ['userProfile']);
    }
    if (this.data.invalidParams || !this.data.answerIdNum || !this.data.questionIdNum) {
      return;
    }
    if (this.data.loading && !this.data.questionDetail) {
      return;
    }
    void this.syncQuestionAuthor();
    void this.loadPage(true);
  },

  async syncQuestionAuthor() {
    const res = await getUserInfo();
    if (!res.ok) {
      return;
    }
    const uidRaw = res.data.userId;
    const uid = uidRaw === undefined || uidRaw === null ? NaN : Number(uidRaw);
    const currentUserId = Number.isFinite(uid) && uid > 0 ? uid : null;
    const q = this.data.questionDetail;
    this.setData({
      currentUserId,
      isQuestionAuthor: currentUserId !== null && q !== null && q.authorId === currentUserId,
    });
  },

  refreshQuestionAuthorFlag(question: ContentDetailModel | null) {
    const uid = this.data.currentUserId;
    this.setData({
      isQuestionAuthor: uid !== null && question !== null && question.authorId === uid,
    });
  },

  onPullDownRefresh() {
    if (this.data.invalidParams || !this.data.answerIdNum || !this.data.questionIdNum) {
      wx.stopPullDownRefresh();
      return;
    }
    void this.refreshAll();
  },

  onReachBottom() {
    if (
      this.data.commentLoading ||
      this.data.commentLoadingMore ||
      this.data.commentFinished ||
      this.data.commentLoadMoreError
    ) {
      return;
    }
    void this.loadComments(false);
  },

  onInvalidAction() {
    wx.navigateBack({ fail: () => {} });
  },

  onRetryLoad() {
    void this.loadPage(false);
  },

  onQuestionAuthorTap(e: WechatMiniprogram.CustomEvent<{ userId?: number }>) {
    navigateToUserProfileFromEvent(e);
  },

  onAnswerAuthorTap(e: WechatMiniprogram.CustomEvent<{ userId?: number }>) {
    navigateToUserProfileFromEvent(e);
  },

  onPublishAnswerFabTap() {
    const qid = this.data.questionIdNum;
    const d = this.data.questionDetail;
    if (!qid || !d) {
      return;
    }
    const raw = d.title?.trim() || '';
    const titleParam = raw ? `&title=${encodeURIComponent(raw)}` : '';
    wx.navigateTo({
      url: `${ROUTES.PUBLISH_ANSWER}?questionId=${qid}${titleParam}`,
      events: {
        published: () => {
          void this.loadPage(true);
        },
      },
    });
  },

  async refreshAll() {
    this.setData({ refreshing: true });
    try {
      await this.loadPage(true);
    } finally {
      this.setData({ refreshing: false });
      wx.stopPullDownRefresh();
    }
  },

  async loadPage(isRefresh: boolean) {
    const aid = this.data.answerIdNum;
    const qid = this.data.questionIdNum;
    if (!aid || !qid) {
      return;
    }
    if (!isRefresh) {
      this.setData({
        loading: true,
        errorType: '',
        errorMessage: '',
        stateTitle: '',
        stateActionText: '',
      });
    }
    const [answerRes, questionRes, listRes] = await Promise.all([
      getAnswerDetail(aid),
      getContentDetail(qid),
      getAnswerList(qid),
    ]);
    if (!answerRes.ok) {
      const err = detailErrorState(answerRes);
      this.setData({
        loading: false,
        errorType: err.errorType,
        stateTitle: err.stateTitle,
        errorMessage: err.errorMessage,
        stateActionText: err.stateActionText,
      });
      return;
    }
    if (!questionRes.ok) {
      const err = detailErrorState(questionRes);
      this.setData({
        loading: false,
        errorType: err.errorType,
        stateTitle: err.stateTitle,
        errorMessage: err.errorMessage,
        stateActionText: err.stateActionText,
      });
      return;
    }
    if (!listRes.ok) {
      const err = detailErrorState(listRes);
      this.setData({
        loading: false,
        errorType: err.errorType,
        stateTitle: err.stateTitle,
        errorMessage: err.errorMessage,
        stateActionText: err.stateActionText,
      });
      return;
    }
    const detail = mapContentVOToDetail(questionRes.data);
    const pinned = mapAnswerVOToModel(answerRes.data);
    if (!detail || !pinned) {
      const fake: RequestFailure = { ok: false, errorType: 'invalidData', message: '数据格式异常' };
      const err = detailErrorState(fake);
      this.setData({
        loading: false,
        errorType: err.errorType,
        stateTitle: err.stateTitle,
        errorMessage: err.errorMessage,
        stateActionText: err.stateActionText,
      });
      return;
    }
    const others: AnswerModel[] = [];
    const all = listRes.data || [];
    for (const row of all) {
      const m = mapAnswerVOToModel(row);
      if (!m) {
        continue;
      }
      if (m.answerId === pinned.answerId) {
        continue;
      }
      others.push(m);
    }
    this.setData({
      loading: false,
      errorType: '',
      questionDetail: detail,
      pinnedAnswer: pinned,
      otherAnswers: others,
      answerCommentCount: Math.max(0, pinned.commentCount || 0),
    });
    this.refreshQuestionAuthorFlag(detail);
    await this.runCommentsReset(isRefresh);
    if (this._pendingFocusComment && !isRefresh) {
      this._pendingFocusComment = false;
      this.openComposer();
    }
  },

  onAcceptAnswerTap(e: WechatMiniprogram.TouchEvent) {
    const raw = e.currentTarget?.dataset?.answerId;
    const aid = typeof raw === 'number' ? raw : Number(raw);
    if (!Number.isFinite(aid) || aid <= 0 || !this.data.isQuestionAuthor) {
      return;
    }
    this.confirmAcceptAnswer(aid);
  },

  confirmAcceptAnswer(answerId: number) {
    if (!this.data.isQuestionAuthor || this.data.acceptingAnswerId !== null) {
      return;
    }
    wx.showModal({
      title: '采纳回答',
      content: '采纳后将突出展示该回答，确定采纳吗？',
      confirmText: '采纳',
      success: (modal) => {
        if (!modal.confirm) {
          return;
        }
        void this.runAcceptAnswer(answerId);
      },
    });
  },

  async runAcceptAnswer(answerId: number) {
    this.setData({ acceptingAnswerId: answerId });
    const res = await acceptAnswer(answerId);
    this.setData({ acceptingAnswerId: null });
    if (!res.ok) {
      wx.showToast({ title: res.message.slice(0, 18) || '操作失败，请重试', icon: 'none' });
      return;
    }
    wx.showToast({ title: '已采纳', icon: 'success' });
    const pinned = this.data.pinnedAnswer;
    if (pinned) {
      this.setData({
        pinnedAnswer: { ...pinned, isAccepted: pinned.answerId === answerId },
        otherAnswers: this.data.otherAnswers.map((row) => ({
          ...row,
          isAccepted: false,
        })),
      });
    }
  },

  runCommentsReset(silent: boolean): Promise<void> {
    const qid = this.data.questionIdNum;
    const aid = this.data.answerIdNum;
    if (!qid || !aid) {
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
      contentId: qid,
      answerId: aid,
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
      this.syncAnswerCommentCount();
    });
  },

  async loadComments(reset: boolean) {
    const qid = this.data.questionIdNum;
    const aid = this.data.answerIdNum;
    if (!qid || !aid || this._commentLock) {
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
      contentId: qid,
      answerId: aid,
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
    const qid = this.data.questionIdNum;
    const aid = this.data.answerIdNum;
    const text = (e.detail?.content ?? this.data.composerValue).trim();
    if (!qid || !aid || !text || this.data.composerSubmitting) {
      return;
    }
    this.setData({ composerSubmitting: true });
    const payload: {
      contentId: number;
      answerId: number;
      content: string;
      parentId?: number;
      replyCommentId?: number;
      replyUserId?: number;
    } = {
      contentId: qid,
      answerId: aid,
      content: text,
    };
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

  syncAnswerCommentCount() {
    const pinned = this.data.pinnedAnswer;
    const fromList = this.data.comments.length;
    const count =
      pinned && pinned.commentCount > fromList ? pinned.commentCount : fromList;
    this.setData({ answerCommentCount: count });
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

  async onAnswerDetailLike() {
    const aid = this.data.answerIdNum;
    const pinned = this.data.pinnedAnswer;
    if (!aid || !pinned || this.data.answerLikeLoading) {
      return;
    }
    this.setData({ answerLikeLoading: true });
    const res = await likeAnswer(aid, pinned.liked !== true);
    this.setData({ answerLikeLoading: false });
    if (!res.ok) {
      wx.showToast({ title: res.message.slice(0, 14) || '操作失败', icon: 'none' });
      return;
    }
    const liked = res.data.isLiked === true;
    const lcRaw = res.data.likedCount;
    const likeCount =
      lcRaw === undefined || lcRaw === null ? undefined : Math.max(0, Number(lcRaw) || 0);
    this.setData({
      pinnedAnswer: {
        ...pinned,
        liked,
        likeCount: likeCount === undefined ? pinned.likeCount : likeCount,
      },
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
          this.setData({ comments: list });
          this.syncAnswerCommentCount();
        })();
      },
    });
  },

  async onCommentLoadReplies(e: WechatMiniprogram.CustomEvent<{ commentId?: number }>) {
    const parentId = e.detail?.commentId;
    const qid = this.data.questionIdNum;
    const aid = this.data.answerIdNum;
    if (!Number.isFinite(parentId) || !parentId || !qid || !aid) {
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
      contentId: qid,
      answerId: aid,
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
