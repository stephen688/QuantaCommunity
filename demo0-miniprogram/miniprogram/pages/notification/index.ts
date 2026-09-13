import { CONTENT_TYPE_LIFE, CONTENT_TYPE_PROFESSIONAL } from '../../constants/content';
import { DEFAULT_PAGE_SIZE } from '../../constants/request';
import { ROUTES } from '../../constants/route';
import {
  getNotificationList,
  mapNotificationPageToItems,
  markAllNotificationsRead,
  markNotificationRead,
} from '../../services/notification.service';
import type { ApiErrorType } from '../../types/api';
import type { NotificationItemModel } from '../../types/notification';
import { feedFullScreenError } from '../../utils/feed-error';
import {
  refreshCustomTabBarUnread,
  syncCustomTabBarSelected,
} from '../../utils/tab-bar';

function inferNotifHasMore(
  pageNum: number,
  pageSize: number,
  fetched: number,
  hasMore?: boolean | null,
  total?: number | null,
): boolean {
  if (hasMore === true) {
    return true;
  }
  if (hasMore === false) {
    return false;
  }
  if (typeof total === 'number' && total >= 0 && pageSize > 0) {
    if (total === 0) {
      return false;
    }
    const totalPage = Math.ceil(total / pageSize);
    return pageNum < totalPage;
  }
  return fetched >= pageSize;
}

function listHasUnread(list: NotificationItemModel[]): boolean {
  return list.some((it) => !it.read);
}

function tryOpenPayload(payload?: string) {
  const raw = (payload || '').trim();
  if (!raw) {
    return;
  }
  try {
    const o = JSON.parse(raw) as { contentId?: number; contentType?: number };
    const cid = o.contentId;
    if (cid === undefined || cid === null || !Number.isFinite(Number(cid))) {
      return;
    }
    const ct = Number(o.contentType);
    if (ct === CONTENT_TYPE_LIFE) {
      wx.navigateTo({ url: `${ROUTES.DETAIL_LIFE}?contentId=${cid}` });
      return;
    }
    if (ct === CONTENT_TYPE_PROFESSIONAL) {
      wx.navigateTo({ url: `${ROUTES.DETAIL_PRO}?contentId=${cid}` });
    }
  } catch {
    /* MVP：payload 非 JSON 时忽略 */
  }
}

Page({
  data: {
    list: [] as NotificationItemModel[],
    loading: true,
    refreshing: false,
    loadingMore: false,
    finished: false,
    errorType: '' as ApiErrorType | '',
    errorMessage: '',
    stateTitle: '',
    stateActionText: '',
    page: 1,
    pageSize: DEFAULT_PAGE_SIZE,
    loadMoreError: false,
    hasUnread: false,
    markAllBusy: false,
  },

  _loadMoreLock: false,

  onLoad() {
    void this.loadFirstPage();
  },

  onShow() {
    this.syncTabBarState();
    void refreshCustomTabBarUnread(this);
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

  async onMarkAllRead() {
    if (this.data.markAllBusy || !this.data.hasUnread) {
      return;
    }
    this.setData({ markAllBusy: true });
    try {
      const res = await markAllNotificationsRead();
      if (!res.ok) {
        wx.showToast({ title: res.message || '操作失败', icon: 'none' });
        return;
      }
      const list = this.data.list.map((it) => ({ ...it, read: true }));
      this.setData({ list, hasUnread: false });
      wx.showToast({ title: '已全部标为已读', icon: 'none' });
      void refreshCustomTabBarUnread(this);
    } finally {
      this.setData({ markAllBusy: false });
    }
  },

  async onItemTap(e: WechatMiniprogram.TouchEvent) {
    const id = Number(e.currentTarget.dataset.id);
    if (!Number.isFinite(id) || id <= 0) {
      return;
    }
    const payload = String(e.currentTarget.dataset.payload || '');
    const res = await markNotificationRead(id);
    if (!res.ok) {
      wx.showToast({ title: res.message || '操作失败', icon: 'none' });
      return;
    }
    const list = this.data.list.map((it) => (it.id === id ? { ...it, read: true } : it));
    this.setData({ list, hasUnread: listHasUnread(list) });
    void refreshCustomTabBarUnread(this);
    tryOpenPayload(payload);
  },

  async loadFirstPage(fromRefresh: boolean = false) {
    if (!fromRefresh) {
      this.setData({
        loading: true,
        list: [],
        page: 1,
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
      const res = await getNotificationList(1, this.data.pageSize);
      if (!res.ok) {
        if (fromRefresh) {
          wx.showToast({ title: '刷新失败', icon: 'none' });
        } else {
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
      const page = res.data;
      const items = mapNotificationPageToItems(page);
      const rawLen = (page.list ?? []).length;
      const finished = !inferNotifHasMore(
        1,
        this.data.pageSize,
        rawLen,
        page.hasMore,
        page.total ?? undefined,
      );
      this.setData({
        list: items,
        page: 1,
        finished,
        loading: false,
        hasUnread: listHasUnread(items),
        errorType: '',
        errorMessage: '',
        stateTitle: '',
        stateActionText: '',
      });
      void refreshCustomTabBarUnread(this);
    } catch {
      if (fromRefresh) {
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
      const next = this.data.page + 1;
      const res = await getNotificationList(next, this.data.pageSize);
      if (!res.ok) {
        this.setData({ loadMoreError: true });
        return;
      }
      const page = res.data;
      const more = mapNotificationPageToItems(page);
      const seen = new Set(this.data.list.map((x) => x.id));
      const merged = [...this.data.list];
      for (const it of more) {
        if (!seen.has(it.id)) {
          seen.add(it.id);
          merged.push(it);
        }
      }
      const rawLen = (page.list ?? []).length;
      const finished = !inferNotifHasMore(
        next,
        this.data.pageSize,
        rawLen,
        page.hasMore,
        page.total ?? undefined,
      );
      this.setData({
        list: merged,
        page: next,
        finished,
        hasUnread: listHasUnread(merged),
      });
      void refreshCustomTabBarUnread(this);
    } catch {
      this.setData({ loadMoreError: true });
    } finally {
      this._loadMoreLock = false;
      this.setData({ loadingMore: false });
    }
  },

  syncTabBarState() {
    syncCustomTabBarSelected(this, 2);
  },
});
