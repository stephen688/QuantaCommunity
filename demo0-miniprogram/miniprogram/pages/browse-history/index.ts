import { DEFAULT_PAGE_SIZE } from '../../constants/request';
import { clearBrowseHistory, getBrowseHistoryList } from '../../services/user-content.service';
import type { ApiErrorType } from '../../types/api';
import type { ContentCardModel } from '../../types/content';
import { feedFullScreenError } from '../../utils/feed-error';
import {
  createPageRefreshSnapshot,
  handleMineListPageOnShow,
  type PageRefreshSnapshot,
} from '../../utils/page-refresh';
import { navigateToUserProfileFromEvent } from '../../utils/user-profile-nav';
import { patchContentCardInList } from '../../utils/content-card-list';
import { navigateFromContentCardEvent } from '../../utils/content-card-navigate';

Page({
  data: {
    list: [] as ContentCardModel[],
    loading: true,
    refreshing: false,
    loadingMore: false,
    finished: false,
    errorType: '' as ApiErrorType | '',
    errorMessage: '',
    stateTitle: '',
    stateActionText: '',
    current: 1,
    pageSize: DEFAULT_PAGE_SIZE,
    loadMoreError: false,
    clearing: false,
  },

  _loadMoreLock: false,
  _refreshSnapshot: createPageRefreshSnapshot() as PageRefreshSnapshot,

  onLoad() {
    void this.loadFirstPage();
  },

  onShow() {
    handleMineListPageOnShow(this, () => {
      void this.loadFirstPage();
    });
  },

  onPullDownRefresh() {
    this.setData({ refreshing: true });
    void this.loadFirstPage(true);
  },

  onReachBottom() {
    if (
      this.data.loading ||
      this.data.refreshing ||
      this.data.loadingMore ||
      this.data.finished ||
      !this.data.list.length
    ) {
      return;
    }
    void this.loadMore();
  },

  onStateAction() {
    void this.loadFirstPage();
  },

  onFooterRetry() {
    this.setData({ loadMoreError: false });
    void this.loadMore();
  },

  async onClearTap() {
    if (!this.data.list.length || this.data.clearing) {
      return;
    }
    const confirmRes = await wx.showModal({
      title: '清空浏览历史',
      content: '确认清空全部浏览记录吗？',
      confirmText: '清空',
    });
    if (!confirmRes.confirm) {
      return;
    }
    this.setData({ clearing: true });
    const res = await clearBrowseHistory();
    this.setData({ clearing: false });
    if (!res.ok) {
      wx.showToast({ title: res.message || '清空失败', icon: 'none' });
      return;
    }
    this.setData({
      list: [],
      current: 1,
      finished: true,
      loadMoreError: false,
    });
    wx.showToast({ title: '已清空', icon: 'none' });
  },

  onCardTap(e: WechatMiniprogram.CustomEvent<{ item: ContentCardModel }>) {
    navigateFromContentCardEvent(e);
  },

  onCardAuthorTap(e: WechatMiniprogram.CustomEvent<{ userId?: number }>) {
    navigateToUserProfileFromEvent(e);
  },

  onCardInteractionChange(e: WechatMiniprogram.CustomEvent<{ item: ContentCardModel }>) {
    const next = e.detail?.item;
    if (!next?.contentId) {
      return;
    }
    this.setData({
      list: patchContentCardInList(this.data.list, next),
    });
  },

  async loadFirstPage(fromRefresh: boolean = false) {
    if (!fromRefresh) {
      this.setData({
        loading: true,
        list: [],
        current: 1,
        finished: false,
        errorType: '',
        errorMessage: '',
        stateTitle: '',
        stateActionText: '',
        loadMoreError: false,
      });
    } else {
      this.setData({
        errorType: '',
        errorMessage: '',
        stateTitle: '',
        stateActionText: '',
        loadMoreError: false,
      });
    }

    try {
      const res = await getBrowseHistoryList(1, this.data.pageSize);
      if (!res.ok) {
        if (fromRefresh && this.data.list.length) {
          wx.showToast({ title: '刷新失败', icon: 'none' });
        } else if (!fromRefresh || !this.data.list.length) {
          const err = feedFullScreenError(res);
          this.setData({
            loading: false,
            errorType: err.errorType,
            stateTitle: err.stateTitle,
            errorMessage: err.errorMessage,
            stateActionText: err.stateActionText,
          });
        }
        return;
      }
      const { list, hasMore } = res.data;
      this.setData({
        list,
        current: 1,
        finished: !hasMore || list.length < this.data.pageSize,
        loading: false,
        errorType: '',
        errorMessage: '',
        stateTitle: '',
        stateActionText: '',
      });
    } catch {
      if (fromRefresh && this.data.list.length) {
        wx.showToast({ title: '刷新失败', icon: 'none' });
      } else {
        this.setData({
          loading: false,
          ...feedFullScreenError({
            ok: false,
            errorType: 'network',
            message: '请求异常，请稍后重试',
          }),
        });
      }
    } finally {
      if (fromRefresh) {
        wx.stopPullDownRefresh();
        this.setData({ refreshing: false });
      }
    }
  },

  async loadMore() {
    if (this._loadMoreLock) {
      return;
    }
    if (
      this.data.loading ||
      this.data.refreshing ||
      this.data.loadingMore ||
      this.data.finished ||
      !this.data.list.length
    ) {
      return;
    }
    this._loadMoreLock = true;
    this.setData({ loadingMore: true, loadMoreError: false });
    try {
      const next = this.data.current + 1;
      const res = await getBrowseHistoryList(next, this.data.pageSize);
      if (!res.ok) {
        this.setData({ loadMoreError: true });
        return;
      }
      const { list: nextList, hasMore } = res.data;
      const merged = [...this.data.list];
      const seen = new Set(merged.map((x) => x.contentId));
      for (const c of nextList) {
        if (!seen.has(c.contentId)) {
          seen.add(c.contentId);
          merged.push(c);
        }
      }
      this.setData({
        list: merged,
        current: next,
        finished: !hasMore || nextList.length < this.data.pageSize,
      });
    } catch {
      this.setData({ loadMoreError: true });
    } finally {
      this._loadMoreLock = false;
      this.setData({ loadingMore: false });
    }
  },
});
