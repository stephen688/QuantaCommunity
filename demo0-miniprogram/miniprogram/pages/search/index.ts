import {
  CONTENT_TYPE_LIFE,
  CONTENT_TYPE_PROFESSIONAL,
} from '../../constants/content';
import { ROUTES } from '../../constants/route';
import { getContentDetail } from '../../services/content.service';
import {
  clearSearchHistory,
  getSearchHistoryKeywords,
  getSearchTrending,
} from '../../services/search.service';
import type { ApiErrorType } from '../../types/api';
import type { SearchHotAlumni, SearchHotQuestion } from '../../types/search';
import { feedFullScreenError } from '../../utils/feed-error';
import { navigateToUserProfile } from '../../utils/user-profile-nav';

function buildSearchResultUrl(keyword: string): string {
  const k = encodeURIComponent(keyword);
  return `${ROUTES.SEARCH_RESULT}?keyword=${k}`;
}

function buildDetailUrlByType(contentId: number, contentType: number): string {
  if (contentType === CONTENT_TYPE_LIFE) {
    return `${ROUTES.DETAIL_LIFE}?contentId=${contentId}`;
  }
  if (contentType === CONTENT_TYPE_PROFESSIONAL) {
    return `${ROUTES.DETAIL_PRO}?contentId=${contentId}`;
  }
  return '';
}

Page({
  data: {
    inputValue: '',
    historyKeywords: [] as string[],
    historyLoading: true,
    historyErrorType: '' as ApiErrorType | '',
    historyStateTitle: '',
    historyErrorMessage: '',
    historyStateActionText: '',
    hotKeywords: [] as string[],
    hotQuestions: [] as SearchHotQuestion[],
    lastHotKeyword: '',
    hotAlumni: [] as SearchHotAlumni[],
    showHotRecommend: false,
    hotHint: '',
    hotQuestionNavigating: false,
  },

  onLoad() {
    void this.loadHistory();
    void this.loadHotRecommend();
  },

  onPullDownRefresh() {
    void Promise.all([this.loadHistory(true), this.loadHotRecommend()]).finally(() => {
      wx.stopPullDownRefresh();
    });
  },

  onSearchInput(e: WechatMiniprogram.CustomEvent<{ value?: string }>) {
    this.setData({ inputValue: e.detail?.value ?? '' });
  },

  onSearchConfirm(e: WechatMiniprogram.CustomEvent<{ value?: string }>) {
    const keyword = (e.detail?.value ?? this.data.inputValue).trim();
    if (!keyword) {
      wx.showToast({ title: '请输入搜索关键词', icon: 'none' });
      return;
    }
    wx.navigateTo({ url: buildSearchResultUrl(keyword) });
  },

  onSearchClear() {
    this.setData({ inputValue: '' });
  },

  onSearchCancel() {
    const pages = getCurrentPages();
    if (pages.length > 1) {
      wx.navigateBack();
    }
  },

  onHistoryTap(e: WechatMiniprogram.TouchEvent) {
    const keyword = String(e.currentTarget.dataset.keyword || '').trim();
    if (!keyword) {
      return;
    }
    wx.navigateTo({ url: buildSearchResultUrl(keyword) });
  },

  onHotKeywordTap(e: WechatMiniprogram.TouchEvent) {
    const keyword = String(e.currentTarget.dataset.keyword || '').trim();
    if (!keyword) {
      return;
    }
    this.setData({ lastHotKeyword: keyword });
    wx.navigateTo({ url: buildSearchResultUrl(keyword) });
  },

  async onHotQuestionTap(e: WechatMiniprogram.TouchEvent) {
    if (this.data.hotQuestionNavigating) {
      return;
    }
    const contentId = Number(e.currentTarget.dataset.contentId);
    if (!Number.isFinite(contentId) || contentId <= 0) {
      return;
    }
    let contentType = Number(e.currentTarget.dataset.contentType);
    if (
      contentType !== CONTENT_TYPE_LIFE &&
      contentType !== CONTENT_TYPE_PROFESSIONAL
    ) {
      this.setData({ hotQuestionNavigating: true });
      wx.showLoading({ title: '加载中', mask: true });
      const res = await getContentDetail(contentId);
      wx.hideLoading();
      this.setData({ hotQuestionNavigating: false });
      if (!res.ok) {
        wx.showToast({ title: res.message || '无法打开详情', icon: 'none' });
        return;
      }
      contentType = Number(res.data?.contentType);
    }
    const url = buildDetailUrlByType(contentId, contentType);
    if (!url) {
      wx.showToast({ title: '内容类型异常', icon: 'none' });
      return;
    }
    wx.navigateTo({ url });
  },

  onHotAlumniTap(e: WechatMiniprogram.TouchEvent) {
    navigateToUserProfile(e.currentTarget.dataset.userId);
  },

  onHistoryStateAction() {
    void this.loadHistory(false);
  },

  async onClearHistory() {
    const keywords = this.data.historyKeywords;
    if (!keywords.length) {
      return;
    }
    const confirmRes = await wx.showModal({
      title: '清空历史',
      content: '确认清空全部搜索历史吗？',
      confirmText: '清空',
    });
    if (!confirmRes.confirm) {
      return;
    }
    const res = await clearSearchHistory();
    if (!res.ok) {
      wx.showToast({ title: res.message || '清空失败', icon: 'none' });
      return;
    }
    this.setData({ historyKeywords: [] });
  },

  async loadHotRecommend() {
    try {
      const res = await getSearchTrending();
      if (!res.ok) {
        this.setData({
          showHotRecommend: false,
          hotKeywords: [],
          hotQuestions: [],
          hotAlumni: [],
          hotHint: '',
        });
        return;
      }
      const data = res.data;
      const hasContent =
        (data.keywords?.length ?? 0) > 0 ||
        (data.questions?.length ?? 0) > 0 ||
        (data.alumni?.length ?? 0) > 0;
      this.setData({
        hotKeywords: data.keywords ?? [],
        hotQuestions: data.questions ?? [],
        hotAlumni: data.alumni ?? [],
        showHotRecommend: hasContent,
        hotHint: '',
      });
    } catch {
      this.setData({
        showHotRecommend: false,
        hotKeywords: [],
        hotQuestions: [],
        hotAlumni: [],
        hotHint: '',
      });
    }
  },

  async loadHistory(fromPullDown: boolean = false) {
    if (!fromPullDown) {
      this.setData({
        historyLoading: true,
        historyErrorType: '',
        historyStateTitle: '',
        historyErrorMessage: '',
        historyStateActionText: '',
      });
    }
    try {
      const res = await getSearchHistoryKeywords();
      if (!res.ok) {
        if (fromPullDown) {
          wx.showToast({ title: res.message || '刷新失败', icon: 'none' });
        } else {
          const err = feedFullScreenError(res);
          this.setData({
            historyLoading: false,
            historyKeywords: [],
            historyErrorType: err.errorType,
            historyStateTitle: err.stateTitle,
            historyErrorMessage: err.errorMessage,
            historyStateActionText: err.stateActionText,
          });
        }
        return;
      }
      const list = Array.isArray(res.data)
        ? res.data.map((item) => String(item || '').trim()).filter(Boolean)
        : [];
      this.setData({
        historyKeywords: list,
        historyLoading: false,
        historyErrorType: '',
        historyStateTitle: '',
        historyErrorMessage: '',
        historyStateActionText: '',
      });
    } catch {
      if (fromPullDown) {
        wx.showToast({ title: '刷新失败', icon: 'none' });
      } else {
        const err = feedFullScreenError({
          ok: false,
          errorType: 'network',
          message: '请求异常，请稍后重试',
        });
        this.setData({
          historyLoading: false,
          historyKeywords: [],
          historyErrorType: err.errorType,
          historyStateTitle: err.stateTitle,
          historyErrorMessage: err.errorMessage,
          historyStateActionText: err.stateActionText,
        });
      }
    }
  },
});
