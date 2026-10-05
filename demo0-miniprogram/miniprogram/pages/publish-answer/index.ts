import { publishAnswer } from '../../services/answer.service';
import { getContentDetail, mapContentVOToDetail } from '../../services/content.service';
import { parseDetailContentId } from '../../utils/detail-shared';
import { querySubmissionStatus } from '../../services/submission.service';
import type { AnswerPublishPayload, AnswerVO } from '../../types/detail';
import {
  clearPendingSubmission,
  getPendingSubmission,
  isSubmissionExpired,
  isPendingSubmissionOwner,
  isPendingSubmissionCurrent,
  prepareSubmission,
  validatePendingSubmissionForSend,
  type PendingSubmission,
} from '../../utils/submission';

const MAX_LEN = 2000;

type PageData = {
  invalid: boolean;
  questionId: number | null;
  questionTitle: string;
  questionMeta: string;
  body: string;
  submitting: boolean;
  loadingSummary: boolean;
  summaryError: string;
  maxLen: number;
  submitDisabled: boolean;
  countWarning: boolean;
  pendingSubmissionMessage: string;
};

const COUNT_WARN_AT = 1840;

function decodeTitle(raw: string | undefined): string {
  if (!raw || typeof raw !== 'string') {
    return '';
  }
  try {
    return decodeURIComponent(raw).trim();
  } catch {
    return raw.trim();
  }
}

Page({
  data: {
    invalid: false,
    questionId: null,
    questionTitle: '',
    questionMeta: '',
    body: '',
    submitting: false,
    loadingSummary: false,
    summaryError: '',
    maxLen: MAX_LEN,
    submitDisabled: true,
    countWarning: false,
    pendingSubmissionMessage: '',
  } as PageData,

  _pendingSubmission: null as PendingSubmission<AnswerPublishPayload> | null,
  _submissionCompleted: false,

  onLoad(query: Record<string, string | undefined>) {
    const qid = parseDetailContentId(query.questionId);
    if (qid === null) {
      this.setData({ invalid: true });
      return;
    }
    const fromQuery = decodeTitle(query.title);
    this.setData({
      invalid: false,
      questionId: qid,
      questionTitle: fromQuery,
      questionMeta: '',
      summaryError: '',
      loadingSummary: !fromQuery,
    });
    if (!fromQuery) {
      void this.loadQuestionTitle(qid);
    } else {
      void this.enrichQuestionMeta(qid);
    }
    void this.restorePendingSubmission(qid);
  },

  onInvalidBack() {
    wx.navigateBack({ fail: () => {} });
  },

  onRetrySummary() {
    const qid = this.data.questionId;
    if (!qid) {
      return;
    }
    void this.loadQuestionTitle(qid);
  },

  buildQuestionMeta(mapped: { quantaDepartment?: string; quantaBatch?: string } | null): string {
    if (!mapped) {
      return '';
    }
    const parts = [mapped.quantaDepartment, mapped.quantaBatch].filter(
      (s): s is string => typeof s === 'string' && !!s.trim(),
    );
    return parts.join(' · ');
  },

  async enrichQuestionMeta(questionId: number) {
    const res = await getContentDetail(questionId);
    if (!res.ok) {
      return;
    }
    const mapped = mapContentVOToDetail(res.data);
    this.setData({ questionMeta: this.buildQuestionMeta(mapped) });
  },

  async loadQuestionTitle(questionId: number) {
    this.setData({ loadingSummary: true, summaryError: '' });
    const res = await getContentDetail(questionId);
    this.setData({ loadingSummary: false });
    if (!res.ok) {
      this.setData({ summaryError: res.message || '标题加载失败' });
      return;
    }
    const mapped = mapContentVOToDetail(res.data);
    const title = mapped?.title?.trim() || '';
    this.setData({
      questionTitle: title,
      questionMeta: this.buildQuestionMeta(mapped),
    });
  },

  onBodyInput(e: WechatMiniprogram.Input) {
    const v = e.detail?.value ?? '';
    this.setData({
      body: v,
      countWarning: v.length >= COUNT_WARN_AT,
      submitDisabled: !v.trim() || this.data.submitting,
    });
  },

  async onSubmit() {
    const qid = this.data.questionId;
    const text = this.data.body.trim();
    if (!qid || this.data.submitting || this.data.loadingSummary || this._submissionCompleted) {
      if (!text && !this._pendingSubmission) {
        wx.showToast({ title: '请输入回答内容', icon: 'none' });
      }
      return;
    }
    if (!this._pendingSubmission && !text) {
      wx.showToast({ title: '请输入回答内容', icon: 'none' });
      return;
    }
    this.setData({ submitting: true, submitDisabled: true });
    try {
      let prepared = this._pendingSubmission;
      if (prepared && !isPendingSubmissionOwner(prepared)) {
        this._pendingSubmission = null;
        wx.showToast({ title: '登录状态已变化，请重新提交', icon: 'none' });
        return;
      }
      if (!prepared) {
        const payload: AnswerPublishPayload = { questionId: qid, content: text };
        const result = await prepareSubmission('answer-publish', `question:${qid}`, payload);
        if (!result.ok) {
          wx.showToast({ title: result.message.slice(0, 18), icon: 'none' });
          return;
        }
        prepared = result.data;
        this._pendingSubmission = prepared;
      }
      const sendCheck = validatePendingSubmissionForSend(prepared);
      if (!sendCheck.ok) {
        if (sendCheck.errorType === 'expired') {
          clearPendingSubmission(prepared);
        }
        this._pendingSubmission = null;
        this.setData({ pendingSubmissionMessage: sendCheck.message });
        wx.showToast({ title: sendCheck.message.slice(0, 18), icon: 'none' });
        return;
      }
      if (this.data.body.trim() !== String(prepared.payload.content || '').trim()) {
        const frozenBody = String(prepared.payload.content || '');
        this.setData({
          body: frozenBody,
          countWarning: frozenBody.length >= COUNT_WARN_AT,
          submitDisabled: false,
          pendingSubmissionMessage: '存在未确认回答，请点击发布重试',
        });
        wx.showToast({ title: '存在未确认回答，请点击发布重试', icon: 'none' });
        return;
      }
      const res = await publishAnswer(prepared.payload, prepared.token);
      if (!isPendingSubmissionOwner(prepared)) {
        this._pendingSubmission = null;
        return;
      }
      if (!res.ok) {
        this.showSubmissionError(res.message || '发布失败，请稍后重试', res.errorType, res.businessCode);
        return;
      }
      this.completeSubmission(res.data, prepared);
    } finally {
      this.setData({
        submitting: false,
        submitDisabled: !this._pendingSubmission && !this.data.body.trim(),
        countWarning: this.data.body.length >= COUNT_WARN_AT,
      });
    }
  },

  async restorePendingSubmission(questionId: number) {
    const record = getPendingSubmission<AnswerPublishPayload>('answer-publish', `question:${questionId}`);
    if (!record) {
      return;
    }
    if (isSubmissionExpired(record)) {
      clearPendingSubmission(record);
      wx.showToast({ title: '提交凭证已过期，请先核对发布记录', icon: 'none' });
      return;
    }
    this._pendingSubmission = record;
    // 状态查询期间也允许点击“发布”，由发送前校验决定是否安全发送冻结 payload。
    this.setData({ submitDisabled: false });
    const status = await querySubmissionStatus<AnswerVO>('answer-publish', record.token);
    if (!isPendingSubmissionCurrent(this._pendingSubmission, record)) {
      if (this._pendingSubmission?.token === record.token) {
        this._pendingSubmission = null;
      }
      return;
    }
    if (!status.ok) {
      this.setData({
        body: String(record.payload.content || ''),
        pendingSubmissionMessage: '存在未确认回答，请点击发布重试',
        submitDisabled: false,
      });
      return;
    }
    if (status.data.status === 'SUCCEEDED' && status.data.data) {
      clearPendingSubmission(record);
      this._pendingSubmission = null;
      this.completeSubmission(status.data.data, record);
      return;
    }
    if (status.data.status === 'EXPIRED') {
      clearPendingSubmission(record);
      this._pendingSubmission = null;
      wx.showToast({ title: '提交凭证已过期，请先核对发布记录', icon: 'none' });
      return;
    }
    this.setData({
      body: String(record.payload.content || ''),
      pendingSubmissionMessage: '存在未确认回答，请点击发布重试',
      submitDisabled: false,
    });
  },

  showSubmissionError(message: string, errorType: string, businessCode?: number) {
    if (errorType === 'submissionExpired' || businessCode === 400) {
      const record = this._pendingSubmission;
      if (record) {
        clearPendingSubmission(record);
      }
      this._pendingSubmission = null;
    }
    const display = message || '发布失败，请稍后重试';
    this.setData({ pendingSubmissionMessage: display });
    wx.showToast({ title: display.slice(0, 18), icon: 'none' });
  },

  completeSubmission(result: AnswerVO, record: PendingSubmission<AnswerPublishPayload>) {
    this._submissionCompleted = true;
    clearPendingSubmission(record);
    this._pendingSubmission = null;
    this.setData({ pendingSubmissionMessage: '' });
    wx.showToast({ title: '已提交审核，通过后展示', icon: 'success' });
    const self = this as WechatMiniprogram.Page.TrivialInstance;
    const ec =
      typeof self.getOpenerEventChannel === 'function' ? self.getOpenerEventChannel() : null;
    if (ec && typeof ec.emit === 'function') {
      ec.emit('published', { answerId: result.answerId });
    }
    setTimeout(() => {
      wx.navigateBack({ fail: () => {} });
    }, 400);
  },
});
