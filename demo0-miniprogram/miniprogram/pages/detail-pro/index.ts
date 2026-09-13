import { CONTENT_TYPE_PROFESSIONAL } from '../../constants/content';
import {
  deleteContent,
  getContentDetail,
  mapContentVOToDetail,
  reportContent,
} from '../../services/content.service';
import {
  acceptAnswer,
  deleteAnswer,
  getAnswerList,
  likeAnswer,
  mapAnswerVOToModel,
} from '../../services/answer.service';
import { setContentLike, setContentCollect } from '../../services/interaction.service';
import { getUserInfo } from '../../services/user.service';
import { ensureRealNameVerified } from '../../services/real-name.service';
import type { ApiErrorType } from '../../types/api';
import type { AnswerModel, AnswerVO, ContentDetailModel } from '../../types/detail';
import {
  applyCollectResult,
  applyLikeResult,
  isRealNameRequiredMessage,
  parseDetailContentId,
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
  answers: AnswerModel[];
  answerLoading: boolean;
  answerError: boolean;
  contentDeleted: boolean;
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

function mapAnswers(list: AnswerVO[] | null | undefined): AnswerModel[] {
  if (!Array.isArray(list)) {
    return [];
  }
  const out: AnswerModel[] = [];
  for (const vo of list) {
    const m = mapAnswerVOToModel(vo);
    if (m) {
      out.push(m);
    }
  }
  return out;
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
    answers: [],
    answerLoading: false,
    answerError: false,
    contentDeleted: false,
    currentUserId: null,
    isQuestionAuthor: false,
    acceptingAnswerId: null,
  } as PageData,

  _answerLikeInFlight: new Set<number>(),
  _pageHasShown: false,
  _refreshSnapshot: createPageRefreshSnapshot() as PageRefreshSnapshot,

  onLoad(query: Record<string, string | undefined>) {
    const id = parseDetailContentId(query.contentId);
    if (id === null) {
      this.setData({
        invalidId: true,
        loading: false,
        contentIdNum: null,
      });
      return;
    }
    this.setData({ contentIdNum: id, invalidId: false, contentDeleted: false });
    void this.syncQuestionAuthor();
    void this.loadDetail(false);
  },

  onShow() {
    if (
      handleDetailProfileRefreshOnShow(this, () => this.applyProfileRefresh())
    ) {
      return;
    }
    if (
      this.data.invalidId ||
      this.data.typeMismatch ||
      !this.data.detail ||
      this.data.loading
    ) {
      return;
    }
    void this.loadAnswers(true);
  },

  async applyProfileRefresh() {
    await this.syncQuestionAuthor();
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
      void this.loadAnswers(true);
    }
  },

  async syncQuestionAuthor() {
    const res = await getUserInfo();
    if (!res.ok) {
      return;
    }
    const uidRaw = res.data.userId;
    const uid = uidRaw === undefined || uidRaw === null ? NaN : Number(uidRaw);
    const currentUserId = Number.isFinite(uid) && uid > 0 ? uid : null;
    const detail = this.data.detail;
    this.setData({
      currentUserId,
      isQuestionAuthor:
        currentUserId !== null && detail !== null && detail.authorId === currentUserId,
    });
  },

  refreshQuestionAuthorFlag(detail: ContentDetailModel | null) {
    const uid = this.data.currentUserId;
    this.setData({
      isQuestionAuthor: uid !== null && detail !== null && detail.authorId === uid,
    });
  },

  applyAcceptedAnswer(answerId: number) {
    const list = this.data.answers.map((row) => ({
      ...row,
      isAccepted: row.answerId === answerId,
    }));
    this.setData({ answers: list });
  },

  onPullDownRefresh() {
    if (this.data.invalidId || !this.data.contentIdNum) {
      wx.stopPullDownRefresh();
      return;
    }
    void this.refreshAll();
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
            answers: [],
            answerLoading: false,
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

  onAnswerAuthorTap(e: WechatMiniprogram.CustomEvent<{ userId?: number }>) {
    navigateToUserProfileFromEvent(e);
  },

  async refreshAll() {
    this.setData({ refreshing: true });
    try {
      await this.loadDetail(true);
      const ok =
        this.data.detail &&
        this.data.detail.contentType === CONTENT_TYPE_PROFESSIONAL &&
        !this.data.typeMismatch;
      if (ok) {
        await this.loadAnswers(true);
      }
    } catch {
      wx.showToast({ title: '刷新失败', icon: 'none' });
    } finally {
      this.setData({ refreshing: false });
      wx.stopPullDownRefresh();
    }
  },

  async loadAnswers(silent: boolean) {
    const qid = this.data.contentIdNum;
    if (!qid || this.data.typeMismatch) {
      return;
    }
    if (!silent) {
      this.setData({ answerLoading: true, answerError: false, answers: [] });
    } else {
      this.setData({ answerLoading: true, answerError: false });
    }
    const res = await getAnswerList(qid);
    if (!res.ok) {
      this.setData({
        answerLoading: false,
        answerError: true,
        answers: silent ? this.data.answers : [],
      });
      if (!silent) {
        wx.showToast({ title: res.message.slice(0, 14) || '回答加载失败', icon: 'none' });
      }
      return;
    }
    this.setData({
      answers: mapAnswers(res.data),
      answerLoading: false,
      answerError: false,
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
    if (mapped.contentType !== CONTENT_TYPE_PROFESSIONAL) {
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
    this.refreshQuestionAuthorFlag(mapped);
    if (!isRefresh) {
      await this.loadAnswers(false);
    }
  },

  async onCommentEntryTap() {
    const verified = await ensureRealNameVerified({
      reason: '发表评论',
      description: '发表评论需先完成校友实名认证，审核通过后即可互动。',
    });
    if (!verified) {
      return;
    }
    this.promptCommentOnAnswer();
  },

  onAnswerCommentTap(e: WechatMiniprogram.TouchEvent) {
    const raw = e.currentTarget?.dataset?.answerId;
    const aid = typeof raw === 'number' ? raw : Number(raw);
    if (!Number.isFinite(aid) || aid <= 0) {
      return;
    }
    this.navigateToAnswerDetail(aid, true);
  },

  promptCommentOnAnswer() {
    const answers = this.data.answers;
    if (answers.length === 0) {
      wx.showToast({ title: '暂无回答，无法评论', icon: 'none' });
      return;
    }
    if (answers.length === 1) {
      this.navigateToAnswerDetail(answers[0].answerId, true);
      return;
    }
    const itemList = answers.map((row, index) => {
      const name = row.authorName?.trim() || `回答 ${index + 1}`;
      const preview = row.content?.trim().slice(0, 14) || '';
      if (!preview) {
        return name;
      }
      const suffix = row.content && row.content.length > 14 ? '…' : '';
      return `${name}：${preview}${suffix}`;
    });
    wx.showActionSheet({
      itemList,
      success: (sheet) => {
        const row = answers[sheet.tapIndex];
        if (row) {
          this.navigateToAnswerDetail(row.answerId, true);
        }
      },
    });
  },

  onPublishAnswerFabTap() {
    const qid = this.data.contentIdNum;
    const d = this.data.detail;
    if (!qid || !d || this.data.typeMismatch) {
      return;
    }
    const raw = d.title?.trim() || '';
    const titleParam = raw ? `&title=${encodeURIComponent(raw)}` : '';
    wx.navigateTo({
      url: `${ROUTES.PUBLISH_ANSWER}?questionId=${qid}${titleParam}`,
      events: {
        published: () => {
          void this.loadAnswers(false);
        },
      },
    });
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

  onAnswerRetry() {
    void this.loadAnswers(false);
  },

  async onAnswerLike(e: WechatMiniprogram.TouchEvent) {
    const raw = e.currentTarget?.dataset?.answerId;
    const aid = typeof raw === 'number' ? raw : Number(raw);
    if (!Number.isFinite(aid) || aid <= 0) {
      return;
    }
    const current = this.data.answers.find((row) => row.answerId === aid);
    if (!current) {
      return;
    }
    const locks = this._answerLikeInFlight;
    if (locks.has(aid)) {
      return;
    }
    locks.add(aid);
    const res = await likeAnswer(aid, current.liked !== true);
    locks.delete(aid);
    if (!res.ok) {
      wx.showToast({ title: res.message.slice(0, 18) || '操作失败', icon: 'none' });
      return;
    }
    const liked = res.data.isLiked === true;
    const lcRaw = res.data.likedCount;
    const likeCount =
      lcRaw === undefined || lcRaw === null ? undefined : Math.max(0, Number(lcRaw) || 0);
    const list = this.data.answers.map((row) => {
      if (row.answerId !== aid) {
        return row;
      }
      return {
        ...row,
        liked,
        likeCount: likeCount === undefined ? row.likeCount : likeCount,
      };
    });
    this.setData({ answers: list });
  },

  onAnswerMore(e: WechatMiniprogram.TouchEvent) {
    const raw = e.currentTarget?.dataset?.answerId;
    const aid = typeof raw === 'number' ? raw : Number(raw);
    if (!Number.isFinite(aid) || aid <= 0) {
      return;
    }
    const row = this.data.answers.find((a) => a.answerId === aid);
    const items: string[] = [];
    if (this.data.isQuestionAuthor && row && !row.isAccepted) {
      items.push('采纳此回答');
    }
    items.push('删除回答');
    wx.showActionSheet({
      itemList: items,
      success: (sheet) => {
        const picked = items[sheet.tapIndex];
        if (picked === '采纳此回答') {
          this.confirmAcceptAnswer(aid);
        } else if (picked === '删除回答') {
          this.confirmDeleteAnswer(aid);
        }
      },
    });
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
    this.applyAcceptedAnswer(answerId);
  },

  confirmDeleteAnswer(answerId: number) {
    wx.showModal({
      title: '删除回答',
      content: '删除后不可恢复，确定删除该回答吗？',
      confirmText: '删除',
      confirmColor: '#e54d42',
      success: (modal) => {
        if (!modal.confirm) {
          return;
        }
        void (async () => {
          const res = await deleteAnswer(answerId);
          if (!res.ok) {
            wx.showToast({ title: res.message.slice(0, 18) || '删除失败', icon: 'none' });
            return;
          }
          wx.showToast({ title: '已删除', icon: 'success' });
          await this.loadAnswers(true);
        })();
      },
    });
  },
  onAnswerCardTap(e: WechatMiniprogram.TouchEvent) {
    const raw = e.currentTarget?.dataset?.answerId;
    const aid = typeof raw === 'number' ? raw : Number(raw);
    if (!Number.isFinite(aid) || aid <= 0) {
      return;
    }
    this.navigateToAnswerDetail(aid);
  },

  navigateToAnswerDetail(answerId: number, focusComment = false) {
    const qid = this.data.contentIdNum;
    if (!qid) {
      return;
    }
    const focus = focusComment ? '&focusComment=1' : '';
    wx.navigateTo({
      url: `${ROUTES.ANSWER_DETAIL}?answerId=${answerId}&questionId=${qid}${focus}`,
    });
  },
});
