import { DEFAULT_PAGE_SIZE } from '../../constants/request';
import { ROUTES } from '../../constants/route';
import {
  getMyContentList,
  type MyContentAuditParam,
} from '../../services/user-content.service';
import type { ApiErrorType } from '../../types/api';
import type { ContentCardModel } from '../../types/content';
import { isRealNameRequiredMessage } from '../../utils/detail-shared';
import { feedFullScreenError } from '../../utils/feed-error';
import {
  beginFeedTabSwitch,
  feedSwitchDone,
  feedSwitchFailUsesToast,
} from '../../utils/feed-switch';
import {
  createPageRefreshSnapshot,
  handleMineListPageOnShow,
  type PageRefreshSnapshot,
} from '../../utils/page-refresh';
import { navigateToUserProfileFromEvent } from '../../utils/user-profile-nav';
import { patchContentCardInList } from '../../utils/content-card-list';
import { navigateFromContentCardEvent } from '../../utils/content-card-navigate';

type AuditTabKey = 'all' | 'pending' | 'approved' | 'rejected';

function auditParamFromTab(tab: AuditTabKey): MyContentAuditParam {
  if (tab === 'pending') {
    return 'PENDING';
  }
  if (tab === 'approved') {
    return 'APPROVED';
  }
  if (tab === 'rejected') {
    return 'REJECTED';
  }
  return '';
}

Page({
  data: {
    auditTab: 'all' as AuditTabKey,
    list: [] as ContentCardModel[],
    switching: false,
    loading: true,
    refreshing: false,
    loadingMore: false,
    finished: false,
    errorType: '' as ApiErrorType | '',
    errorMessage: '',
    stateTitle: '',
    stateActionText: '',
    needAuthVerify: false,
    current: 1,
    pageSize: DEFAULT_PAGE_SIZE,
    loadMoreError: false,
  },

  _loadMoreLock: false,
  _refreshSnapshot: createPageRefreshSnapshot() as PageRefreshSnapshot,

  onLoad(query: Record<string, string | undefined>) {
    const auditRaw = query?.audit;
    if (auditRaw === '0' || auditRaw === 'pending') {
      this.setData({ auditTab: 'pending' });
    }
    void this.loadFirstPage();
  },

  onShow() {
    handleMineListPageOnShow(this, () => {
      void this.loadFirstPage();
    });
  },

  onPullDownRefresh() {
    this.setData({ refreshing: true });
    void this.loadFirstPage({ fromRefresh: true });
  },

  onReachBottom() {
    if (
      this.data.loading ||
      this.data.switching ||
      this.data.refreshing ||
      this.data.loadingMore ||
      this.data.finished ||
      !this.data.list.length
    ) {
      return;
    }
    void this.loadMore();
  },

  onAuditTap(e: WechatMiniprogram.TouchEvent) {
    const tab = e.currentTarget.dataset.tab as AuditTabKey;
    if (!tab || tab === this.data.auditTab) {
      return;
    }
    wx.pageScrollTo({ scrollTop: 0, duration: 0 });
    const hadContent = this.data.list.length > 0;
    this.setData({
      auditTab: tab,
      ...beginFeedTabSwitch(),
      ...(hadContent ? {} : { list: [] }),
    });
    void this.loadFirstPage({ tabSwitch: true });
  },

  onStateAction() {
    if (this.data.needAuthVerify) {
      wx.navigateTo({ url: `${ROUTES.PROFILE_EDIT}?intent=verify` });
      return;
    }
    void this.loadFirstPage();
  },

  onEmptyPublish() {
    wx.navigateTo({ url: ROUTES.PUBLISH });
  },

  onFooterRetry() {
    this.setData({ loadMoreError: false });
    void this.loadMore();
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

  async loadFirstPage(opts?: { fromRefresh?: boolean; tabSwitch?: boolean }) {
    const fromRefresh = opts?.fromRefresh === true;
    const wasSwitching = opts?.tabSwitch === true || this.data.switching;
    if (!fromRefresh) {
      if (wasSwitching) {
        this.setData({
          current: 1,
          finished: false,
          errorType: '',
          errorMessage: '',
          stateTitle: '',
          stateActionText: '',
          needAuthVerify: false,
          loadMoreError: false,
        });
      } else {
        this.setData({
          loading: true,
          list: [],
          current: 1,
          finished: false,
          errorType: '',
          errorMessage: '',
          stateTitle: '',
          stateActionText: '',
          needAuthVerify: false,
          loadMoreError: false,
        });
      }
    } else {
      this.setData({
        errorType: '',
        errorMessage: '',
        stateTitle: '',
        stateActionText: '',
        needAuthVerify: false,
        loadMoreError: false,
      });
    }

    const audit = auditParamFromTab(this.data.auditTab);

    try {
      const res = await getMyContentList(1, this.data.pageSize, audit);
      if (!res.ok) {
        if (feedSwitchFailUsesToast(fromRefresh, wasSwitching)) {
          this.setData(feedSwitchDone());
          wx.showToast({ title: fromRefresh ? '刷新失败' : '加载失败', icon: 'none' });
        } else if (!fromRefresh || !this.data.list.length) {
          if (isRealNameRequiredMessage(res.message)) {
            this.setData({
              ...feedSwitchDone(),
              errorType: 'server',
              stateTitle: '需完成实名认证',
              errorMessage: res.message || '请先完成实名认证',
              stateActionText: '去认证',
              needAuthVerify: true,
            });
          } else {
            const err = feedFullScreenError(res);
            this.setData({
              ...feedSwitchDone(),
              errorType: err.errorType,
              stateTitle: err.stateTitle,
              errorMessage: err.errorMessage,
              stateActionText: err.stateActionText,
              needAuthVerify: false,
            });
          }
        }
        return;
      }
      const { list, hasMore } = res.data;
      this.setData({
        list,
        current: 1,
        finished: !hasMore || list.length < this.data.pageSize,
        ...feedSwitchDone(),
        errorType: '',
        errorMessage: '',
        stateTitle: '',
        stateActionText: '',
        needAuthVerify: false,
      });
    } catch {
      if (feedSwitchFailUsesToast(fromRefresh, wasSwitching)) {
        this.setData(feedSwitchDone());
        wx.showToast({ title: fromRefresh ? '刷新失败' : '加载失败', icon: 'none' });
      } else {
        this.setData({
          ...feedSwitchDone(),
          ...feedFullScreenError({
            ok: false,
            errorType: 'network',
            message: '请求异常，请稍后重试',
          }),
          needAuthVerify: false,
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
      this.data.switching ||
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
      const audit = auditParamFromTab(this.data.auditTab);
      const res = await getMyContentList(next, this.data.pageSize, audit);
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
