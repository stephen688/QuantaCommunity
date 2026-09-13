import {
  CONTENT_TYPE_ALL,
  CONTENT_TYPE_LIFE,
  CONTENT_TYPE_PROFESSIONAL,
  type ContentTypeFilter,
} from '../../constants/content';
import { DEFAULT_PAGE_SIZE } from '../../constants/request';
import { ROUTES } from '../../constants/route';
import { mapContentVOToCard } from '../../services/content.service';
import {
  buildFollowFeedQuery,
  getFollowFeed,
} from '../../services/follow.service';
import type { ApiErrorType } from '../../types/api';
import type { ContentCardModel } from '../../types/content';
import { feedFullScreenError } from '../../utils/feed-error';
import {
  beginFeedTabSwitch,
  feedInitialLoading,
  feedSwitchDone,
  feedSwitchFailUsesToast,
} from '../../utils/feed-switch';
import { mergeFeedList, resolveFinished } from '../../utils/pagination';
import {
  refreshCustomTabBarUnread,
  syncCustomTabBarSelected,
} from '../../utils/tab-bar';
import { consumeFollowFeedStale } from '../../utils/follow-feed-bus';
import { navigateFromContentCardEvent } from '../../utils/content-card-navigate';
import { navigateToUserProfileFromEvent } from '../../utils/user-profile-nav';
import { patchContentCardInList } from '../../utils/content-card-list';
import { startFeedTimer, stopFeedTimer } from '../../utils/feed-timer';

type EmptyReason = '' | 'noFollow' | 'noContentInFilter';
function isCardModel(item: ContentCardModel | null): item is ContentCardModel {
  return Boolean(item);
}

function emptyCopy(filter: ContentTypeFilter): {
  title: string;
  desc: string;
  action: string;
  reason: EmptyReason;
} {
  if (filter === CONTENT_TYPE_LIFE) {
    return {
      title: '关注的人还没有生活内容',
      desc: '切换为全部或下拉刷新试试',
      action: '查看全部',
      reason: 'noContentInFilter',
    };
  }
  if (filter === CONTENT_TYPE_PROFESSIONAL) {
    return {
      title: '关注的人还没有专业问题',
      desc: '切换为全部或下拉刷新试试',
      action: '查看全部',
      reason: 'noContentInFilter',
    };
  }
  return {
    title: '还没有关注动态',
    desc: '去发现页看看推荐内容',
    action: '去首页看看',
    reason: 'noFollow',
  };
}

/** 防止快速切换分区时，较早发出的关注流请求覆盖较新结果 */
let followFeedRequestToken = 0;

Page({
  data: {
    filter: CONTENT_TYPE_ALL as ContentTypeFilter,
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
    emptyTitle: '',
    emptyDesc: '',
    emptyActionText: '',
    emptyReason: '' as EmptyReason,
    lastId: undefined as number | undefined,
    offset: 0,
    pageSize: DEFAULT_PAGE_SIZE,
    loadMoreError: false,
  },

  _loadMoreLock: false,

  onLoad() {
    const copy = emptyCopy(CONTENT_TYPE_ALL);
    this.setData({
      emptyTitle: copy.title,
      emptyDesc: copy.desc,
      emptyActionText: copy.action,
      emptyReason: copy.reason,
    });
    void this.loadFirstPage();
  },

  onShow() {
    this.syncTabBarState();
    void refreshCustomTabBarUnread(this);
    startFeedTimer(() => this.silentRefresh());
    if (consumeFollowFeedStale()) {
      void this.loadFirstPage();
    }
  },

  onHide() {
    stopFeedTimer();
  },

  onUnload() {
    stopFeedTimer();
  },

  onPullDownRefresh() {
    this.setData({ refreshing: true });
    void this.loadFirstPage();
  },

  onReachBottom() {
    if (
      this.data.loading ||
      this.data.switching ||
      this.data.loadingMore ||
      this.data.finished ||
      this.data.refreshing
    ) {
      return;
    }
    if (!this.data.list.length) {
      return;
    }
    void this.loadMore();
  },

  onFilterChange(e: WechatMiniprogram.CustomEvent<{ value: ContentTypeFilter }>) {
    const next = e.detail?.value;
    if (next === undefined || next === this.data.filter) {
      return;
    }
    const copy = emptyCopy(next);
    const hadContent = this.data.list.length > 0;
    this.setData({
      filter: next,
      finished: false,
      lastId: undefined,
      offset: 0,
      errorType: '',
      errorMessage: '',
      stateTitle: '',
      stateActionText: '',
      loadMoreError: false,
      ...beginFeedTabSwitch(),
      ...(hadContent ? {} : { list: [] }),
      emptyTitle: copy.title,
      emptyDesc: copy.desc,
      emptyActionText: copy.action,
      emptyReason: copy.reason,
    });
    wx.pageScrollTo({ scrollTop: 0, duration: 0 });
    void this.loadFirstPage({ tabSwitch: true });
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

  onStateAction() {
    void this.loadFirstPage();
  },

  onEmptyAction() {
    const { emptyReason } = this.data;
    if (emptyReason === 'noFollow') {
      wx.switchTab({ url: ROUTES.HOME });
      return;
    }
    if (emptyReason === 'noContentInFilter') {
      const copy = emptyCopy(CONTENT_TYPE_ALL);
      const hadContent = this.data.list.length > 0;
      this.setData({
        filter: CONTENT_TYPE_ALL,
        finished: false,
        lastId: undefined,
        offset: 0,
        errorType: '',
        errorMessage: '',
        loadMoreError: false,
        ...beginFeedTabSwitch(),
        ...(hadContent ? {} : { list: [] }),
        emptyTitle: copy.title,
        emptyDesc: copy.desc,
        emptyActionText: copy.action,
        emptyReason: copy.reason,
      });
      wx.pageScrollTo({ scrollTop: 0, duration: 0 });
      void this.loadFirstPage({ tabSwitch: true });
    }
  },

  onRetryLoadMore() {
    this.setData({ loadMoreError: false });
    void this.loadMore();
  },

  async silentRefresh() {
    if (
      this.data.loading ||
      this.data.switching ||
      this.data.refreshing ||
      this.data.loadingMore ||
      !this.data.list.length
    ) {
      return;
    }
    try {
      const { filter, pageSize } = this.data;
      const res = await getFollowFeed(
        buildFollowFeedQuery(filter, undefined, 0, pageSize),
      );
      if (!res.ok) {
        return;
      }
      const page = res.data;
      const rawList = page.list;
      const cards = rawList.map(mapContentVOToCard).filter(isCardModel);
      if (!cards.length) {
        return;
      }
      const finished = resolveFinished(cards, pageSize, page.hasMore);
      this.setData({
        list: cards,
        lastId: page.nextLastId,
        offset: page.nextOffset,
        finished,
      });
    } catch {
      // silent refresh failure is intentionally ignored
    }
  },

  syncTabBarState() {
    syncCustomTabBarSelected(this, 1);
  },

  async loadFirstPage(opts?: { tabSwitch?: boolean }) {
    const wasRefreshing = this.data.refreshing;
    const wasSwitching = opts?.tabSwitch === true || this.data.switching;
    const token = ++followFeedRequestToken;

    if (!wasRefreshing) {
      this.setData({
        loading: feedInitialLoading(this.data.list.length, wasRefreshing, wasSwitching),
        errorType: '',
        errorMessage: '',
        loadMoreError: false,
      });
    } else {
      this.setData({ errorType: '', errorMessage: '', loadMoreError: false });
    }

    try {
      const { filter, pageSize } = this.data;
      const res = await getFollowFeed(
        buildFollowFeedQuery(filter, undefined, 0, pageSize),
      );

      if (token !== followFeedRequestToken) {
        return;
      }

      if (!res.ok) {
        if (feedSwitchFailUsesToast(wasRefreshing, wasSwitching)) {
          this.setData(feedSwitchDone());
          wx.showToast({ title: wasRefreshing ? '刷新失败' : '加载失败', icon: 'none' });
        } else {
          this.setData({
            ...feedSwitchDone(),
            emptyReason: '',
            ...feedFullScreenError(res),
          });
        }
        return;
      }

      const page = res.data;
      const rawList = page.list;
      const cards = rawList.map(mapContentVOToCard).filter(isCardModel);

      if (rawList.length > 0 && cards.length === 0) {
        if (feedSwitchFailUsesToast(wasRefreshing, wasSwitching)) {
          this.setData(feedSwitchDone());
          wx.showToast({ title: '暂无有效内容', icon: 'none' });
          return;
        }
        this.setData({
          ...feedSwitchDone(),
          list: [],
          finished: true,
          lastId: undefined,
          offset: 0,
          loadMoreError: false,
          emptyReason: '',
          ...feedFullScreenError({
            ok: false,
            errorType: 'invalidData',
            message: '暂无可展示的有效内容',
          }),
        });
        return;
      }

      const finished = resolveFinished(cards, pageSize, page.hasMore);
      const copy = emptyCopy(filter);

      this.setData({
        list: cards,
        lastId: page.nextLastId,
        offset: page.nextOffset,
        finished,
        ...feedSwitchDone(),
        errorType: '',
        errorMessage: '',
        stateTitle: '',
        stateActionText: '',
        loadMoreError: false,
        emptyTitle: copy.title,
        emptyDesc: copy.desc,
        emptyActionText: copy.action,
        emptyReason: cards.length ? '' : copy.reason,
      });
    } catch {
      if (token !== followFeedRequestToken) {
        return;
      }
      if (feedSwitchFailUsesToast(wasRefreshing, wasSwitching)) {
        this.setData(feedSwitchDone());
        wx.showToast({ title: wasRefreshing ? '刷新失败' : '加载失败', icon: 'none' });
      } else {
        this.setData({
          ...feedSwitchDone(),
          emptyReason: '',
          ...feedFullScreenError({
            ok: false,
            errorType: 'network',
            message: '请求异常，请稍后重试',
          }),
        });
      }
    } finally {
      if (wasRefreshing) {
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
      this.data.loadingMore ||
      this.data.finished ||
      this.data.refreshing ||
      !this.data.list.length
    ) {
      return;
    }

    this._loadMoreLock = true;
    this.setData({ loadingMore: true, loadMoreError: false });

    try {
      const { filter, pageSize, lastId, offset, list } = this.data;
      const res = await getFollowFeed(
        buildFollowFeedQuery(filter, lastId, offset, pageSize),
      );

      if (!res.ok) {
        this.setData({ loadMoreError: true });
        return;
      }

      const page = res.data;
      const cards = page.list.map(mapContentVOToCard).filter(isCardModel);
      const merged = mergeFeedList(list, cards);
      const finished = resolveFinished(cards, pageSize, page.hasMore);

      this.setData({
        list: merged,
        lastId: page.nextLastId,
        offset: page.nextOffset,
        finished,
      });
    } catch {
      this.setData({ loadMoreError: true });
    } finally {
      this.setData({ loadingMore: false });
      this._loadMoreLock = false;
    }
  },
});
