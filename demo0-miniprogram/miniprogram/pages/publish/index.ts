import { CONTENT_TYPE_LIFE, CONTENT_TYPE_PROFESSIONAL } from '../../constants/content';
import type { ContentType } from '../../constants/content';
import { ROUTES } from '../../constants/route';
import { publishContent } from '../../services/content.service';
import {
  PUBLISH_BODY_MAX_LEN,
  PUBLISH_IMAGE_MAX_COUNT,
  PUBLISH_TITLE_MAX_LEN,
} from '../../types/publish';
import { isRealNameRequiredMessage } from '../../utils/detail-shared';
import { ensureRealNameVerified } from '../../services/real-name.service';
import { querySubmissionStatus } from '../../services/submission.service';
import type { ContentPublishPayload } from '../../types/publish';
import type { ContentVO } from '../../types/content';
import {
  clearPendingSubmission,
  getPendingSubmission,
  isPendingSubmissionOwner,
  isPendingSubmissionCurrent,
  isSubmissionExpired,
  prepareSubmission,
  validatePendingSubmissionForSend,
  type PendingSubmission,
} from '../../utils/submission';

type PageData = {
  contentType: ContentType;
  title: string;
  content: string;
  images: string[];
  submitting: boolean;
  uploading: boolean;
  hasUploadError: boolean;
  errorMessage: string;
  titleCount: number;
  contentCount: number;
  titlePlaceholder: string;
  keyboardInset: number;
  pendingSubmissionMessage: string;
};

function resolveType(raw: string | undefined): ContentType {
  return Number(raw) === CONTENT_TYPE_PROFESSIONAL ? CONTENT_TYPE_PROFESSIONAL : CONTENT_TYPE_LIFE;
}

function titlePlaceholderByType(contentType: ContentType): string {
  return contentType === CONTENT_TYPE_PROFESSIONAL ? '输入专业问题标题' : '说清楚你遇到的问题';
}

Page({
  data: {
    contentType: CONTENT_TYPE_LIFE,
    title: '',
    content: '',
    images: [],
    submitting: false,
    uploading: false,
    hasUploadError: false,
    errorMessage: '',
    titleCount: 0,
    contentCount: 0,
    titlePlaceholder: titlePlaceholderByType(CONTENT_TYPE_LIFE),
    keyboardInset: 0,
    pendingSubmissionMessage: '',
  } as PageData,

  _pendingSubmission: null as PendingSubmission<ContentPublishPayload> | null,
  _submissionCompleted: false,

  onKeyboardHeightChange(res: { height?: number }) {
    const h = Math.max(0, res.height || 0);
    if (h !== this.data.keyboardInset) {
      this.setData({ keyboardInset: h });
    }
  },

  onLoad(query: Record<string, string | undefined>) {
    const nextType = resolveType(query.contentType);
    this.setData({
      contentType: nextType,
      titlePlaceholder: titlePlaceholderByType(nextType),
    });
    void this.restorePendingSubmission();
  },

  onTypeTap(e: WechatMiniprogram.TouchEvent) {
    const nextType = resolveType((e.currentTarget?.dataset?.type as string | undefined) ?? '');
    if (nextType === this.data.contentType || this.data.submitting || this.data.uploading) {
      return;
    }
    this.setData({
      contentType: nextType,
      titlePlaceholder: titlePlaceholderByType(nextType),
      errorMessage: '',
    });
  },

  onTitleInput(e: WechatMiniprogram.CustomEvent<{ value?: string }>) {
    const value = e.detail?.value ?? '';
    this.setData({
      title: value,
      titleCount: value.length,
      errorMessage: '',
    });
  },

  onContentInput(e: WechatMiniprogram.CustomEvent<{ value?: string }>) {
    const value = e.detail?.value ?? '';
    this.setData({
      content: value,
      contentCount: value.length,
      errorMessage: '',
    });
  },

  onImagesChange(e: WechatMiniprogram.CustomEvent<{ value?: string[] }>) {
    const raw = e.detail?.value;
    const list = Array.isArray(raw) ? raw.filter((x): x is string => typeof x === 'string') : [];
    this.setData({
      images: list.slice(0, PUBLISH_IMAGE_MAX_COUNT),
      errorMessage: '',
    });
  },

  onUploadStateChange(
    e: WechatMiniprogram.CustomEvent<{ uploading?: boolean; hasError?: boolean }>,
  ) {
    this.setData({
      uploading: e.detail?.uploading === true,
      hasUploadError: e.detail?.hasError === true,
    });
  },

  validateForm(): string {
    const title = this.data.title.trim();
    const body = this.data.content.trim();
    if (!title) {
      return '请填写标题';
    }
    if (title.length > PUBLISH_TITLE_MAX_LEN) {
      return `标题最多 ${PUBLISH_TITLE_MAX_LEN} 字`;
    }
    if (!body) {
      return '请填写正文';
    }
    if (body.length > PUBLISH_BODY_MAX_LEN) {
      return `正文最多 ${PUBLISH_BODY_MAX_LEN} 字`;
    }
    if (this.data.images.length > PUBLISH_IMAGE_MAX_COUNT) {
      return `最多上传 ${PUBLISH_IMAGE_MAX_COUNT} 张图片`;
    }
    if (this.data.uploading) {
      return '图片上传中，请稍候';
    }
    if (this.data.hasUploadError) {
      return '存在上传失败图片，请重试或删除后再发布';
    }
    return '';
  },

  async onSubmit() {
    if (this.data.submitting || this._submissionCompleted) {
      return;
    }
    if (!this._pendingSubmission) {
      const error = this.validateForm();
      if (error) {
        this.setData({ errorMessage: error });
        wx.showToast({ title: error.slice(0, 14), icon: 'none' });
        return;
      }
    }
    this.setData({ submitting: true, errorMessage: '', pendingSubmissionMessage: '' });
    try {
      let prepared = this._pendingSubmission;
      if (prepared && !isPendingSubmissionOwner(prepared)) {
        this._pendingSubmission = null;
        wx.showToast({ title: '登录状态已变化，请重新提交', icon: 'none' });
        return;
      }
      if (!prepared) {
        const verified = await ensureRealNameVerified({
          reason: '发布内容',
          description: '发布帖子需先完成校友实名认证，审核通过后即可发布。',
        });
        if (!verified) {
          return;
        }
        const payload: ContentPublishPayload = {
          contentType: this.data.contentType,
          title: this.data.title.trim(),
          content: this.data.content.trim(),
          images: this.data.images.slice(0, PUBLISH_IMAGE_MAX_COUNT),
        };
        const result = await prepareSubmission('content-publish', 'publish', payload);
        if (!result.ok) {
          this.setData({ errorMessage: result.message });
          wx.showToast({ title: result.message.slice(0, 14), icon: 'none' });
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
        this.setData({
          errorMessage: sendCheck.message,
          pendingSubmissionMessage: sendCheck.message,
        });
        wx.showToast({ title: sendCheck.message.slice(0, 14), icon: 'none' });
        return;
      }
      if (!this.matchesPendingPayload(prepared)) {
        const payload = prepared.payload;
        this.setData({
          contentType: payload.contentType,
          title: payload.title,
          content: payload.content,
          images: payload.images.slice(),
          titleCount: payload.title.length,
          contentCount: payload.content.length,
          titlePlaceholder: titlePlaceholderByType(payload.contentType),
          pendingSubmissionMessage: '存在未确认提交，请点击发布重试',
        });
        wx.showToast({ title: '存在未确认提交，请点击发布重试', icon: 'none' });
        return;
      }
      const res = await publishContent(prepared.payload, prepared.token);
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
      this.setData({ submitting: false });
    }
  },

  async restorePendingSubmission() {
    if (this._pendingSubmission) {
      return;
    }
    const record = getPendingSubmission<ContentPublishPayload>('content-publish', 'publish');
    if (!record) {
      return;
    }
    if (isSubmissionExpired(record)) {
      clearPendingSubmission(record);
      wx.showToast({ title: '提交凭证已过期，请先核对发布记录', icon: 'none' });
      return;
    }
    this._pendingSubmission = record;
    const status = await querySubmissionStatus<ContentVO>('content-publish', record.token);
    if (!isPendingSubmissionCurrent(this._pendingSubmission, record)) {
      if (this._pendingSubmission?.token === record.token) {
        this._pendingSubmission = null;
      }
      return;
    }
    if (!status.ok) {
      this.applyPendingPayload(record);
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
    this.applyPendingPayload(record);
  },

  applyPendingPayload(record: PendingSubmission<ContentPublishPayload>) {
    const payload = record.payload;
    this.setData({
      contentType: payload.contentType,
      title: payload.title,
      content: payload.content,
      images: payload.images.slice(),
      titleCount: payload.title.length,
      contentCount: payload.content.length,
      titlePlaceholder: titlePlaceholderByType(payload.contentType),
      pendingSubmissionMessage: '存在未确认提交，请点击发布重试',
    });
  },

  matchesPendingPayload(record: PendingSubmission<ContentPublishPayload>): boolean {
    const payload = record.payload;
    const images = payload.images || [];
    return (
      this.data.contentType === payload.contentType &&
      this.data.title.trim() === payload.title.trim() &&
      this.data.content.trim() === payload.content.trim() &&
      this.data.images.length === images.length &&
      this.data.images.every((image, index) => image === images[index])
    );
  },

  showSubmissionError(message: string, errorType: string, businessCode?: number) {
    const msg = message || '发布失败，请稍后重试';
    if (errorType === 'submissionExpired' || businessCode === 400) {
      const record = this._pendingSubmission;
      if (record) {
        clearPendingSubmission(record);
      }
      this._pendingSubmission = null;
    }
    let display = msg;
    if (errorType === 'unauthorized') {
      display = '登录状态失效，请重新登录';
    } else if (isRealNameRequiredMessage(msg)) {
      display = '请先完成实名认证';
    }
    this.setData({ errorMessage: display, pendingSubmissionMessage: display });
    wx.showToast({ title: display.slice(0, 14), icon: 'none' });
  },

  completeSubmission(result: ContentVO, record: PendingSubmission<ContentPublishPayload>) {
    this._submissionCompleted = true;
    clearPendingSubmission(record);
    this._pendingSubmission = null;
    this.setData({ errorMessage: '', pendingSubmissionMessage: '' });
    const contentId = Number(result.contentId);
    if (!Number.isFinite(contentId) || contentId <= 0) {
      wx.showToast({ title: '已提交审核', icon: 'success' });
      wx.navigateBack({ fail: () => {} });
      return;
    }
    wx.showToast({ title: '已提交审核，通过后展示', icon: 'success' });
    setTimeout(() => {
      wx.redirectTo({ url: `${ROUTES.MY_CONTENT}?audit=0` });
    }, 200);
  },
});
