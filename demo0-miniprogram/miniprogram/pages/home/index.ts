import {
  CONTENT_TYPE_ALL,
  CONTENT_TYPE_LIFE,
  CONTENT_TYPE_PROFESSIONAL,
  type ContentTypeFilter,
} from '../../constants/content';
import { DEFAULT_PAGE_SIZE } from '../../constants/request';
import { ROUTES } from '../../constants/route';
import {
  buildRecommendQuery,
  getRecommendFeed,
  mapContentVOToCard,
} from '../../services/content.service';
import type { ApiErrorType } from '../../types/api';
import type { ContentCardModel } from '../../types/content';
import type { RecommendScene } from '../../types/feed';
import { feedFullScreenError } from '../../utils/feed-error';
import {
  beginFeedTabSwitch,
  feedInitialLoading,
  feedSwitchDone,
  feedSwitchFailUsesToast,
} from '../../utils/feed-switch';
import { mergeFeedList, resolveFinished } from '../../utils/pagination';
import { isLoggedIn } from '../../services/auth.service';
import {
  refreshCustomTabBarUnread,
  syncCustomTabBarSelected,
} from '../../utils/tab-bar';
import { navigateToUserProfileFromEvent } from '../../utils/user-profile-nav';
import { patchContentCardInList } from '../../utils/content-card-list';
import { navigateFromContentCardEvent } from '../../utils/content-card-navigate';
import { startFeedTimer, stopFeedTimer } from '../../utils/feed-timer';

function isCardModel(item: ContentCardModel | null): item is ContentCardModel {
  return Boolean(item);
}

function emptyCopy(
  filter: ContentTypeFilter,
  scene: RecommendScene = 'latest',
): { title: string; desc: string } {
  const isHot = scene === 'hot';
  if (filter === CONTENT_TYPE_LIFE) {
    return {
      title: isHot ? '暂无热门生活内容' : '暂无生活内容',
      desc: '切换分区或下拉刷新试试',
    };
  }
  if (filter === CONTENT_TYPE_PROFESSIONAL) {
    return {
      title: isHot ? '暂无热门专业内容' : '暂无专业内容',
      desc: '切换分区或下拉刷新试试',
    };
  }
  return {
    title: isHot ? '暂无热门推荐' : '暂无推荐',
    desc: isHot ? '切换分区或下拉刷新试试' : '下拉刷新或去发布一条内容',
  };
}

/** 防止快速切换分区时，较早发出的推荐请求覆盖较新结果 */
let homeFeedRequestToken = 0;

Page({
  data: {
    filter: CONTENT_TYPE_ALL as ContentTypeFilter,
    /** 推荐场景：latest 默认 / hot 热度（对应 GET /content/recommend?scene=） */
    scene: 'latest' as RecommendScene,
    feedSceneTabs: [
      { key: 'latest' as RecommendScene, label: '默认' },
      { key: 'hot' as RecommendScene, label: '热度' },
    ],
    categoryTabs: [
      { key: CONTENT_TYPE_ALL, label: '全部', icon: 'all' as const },
      { key: CONTENT_TYPE_LIFE, label: '生活', icon: 'life' as const },
      { key: CONTENT_TYPE_PROFESSIONAL, label: '专业', icon: 'pro' as const },
    ],
    list: [] as ContentCardModel[],
    /** 切换分类/热度时保留旧列表，仅弱化展示 + 顶栏细条加载 */
    switching: false,
    loading: true,
    refreshing: false,
    loadingMore: false,
    finished: false,
    errorType: '' as ApiErrorType | '',
    errorMessage: '',
    stateTitle: '',
    stateActionText: '',
    emptyTitle: emptyCopy(CONTENT_TYPE_ALL, 'latest').title,
    emptyDesc: emptyCopy(CONTENT_TYPE_ALL, 'latest').desc,
    lastScore: undefined as number | undefined,
    offset: 0,
    pageSize: DEFAULT_PAGE_SIZE,
    loadMoreError: false,
  },

  _loadMoreLock: false,

  onLoad() {
    void this.loadFirstPage();
  },

  onShow() {
    this.syncTabBarState();
    void refreshCustomTabBarUnread(this);
    startFeedTimer(() => this.silentRefresh());
    if (
      isLoggedIn() &&
      !this.data.loading &&
      !this.data.switching &&
      !this.data.refreshing &&
      this.data.list.length === 0 &&
      !this.data.errorType
    ) {
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

  resetFeedAndReload(
    patch: Partial<{
      filter: ContentTypeFilter;
      scene: RecommendScene;
    }>,
  ) {
    homeFeedRequestToken += 1;
    const filter = patch.filter ?? this.data.filter;
    const scene = patch.scene ?? this.data.scene;
    const copy = emptyCopy(filter, scene);
    const hadContent = this.data.list.length > 0;
    this.setData({
      ...patch,
      filter,
      scene,
      finished: false,
      lastScore: undefined,
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
    });
    wx.pageScrollTo({ scrollTop: 0, duration: 0 });
    void this.loadFirstPage({ tabSwitch: true });
  },

  onHomeCategoryTap(e: WechatMiniprogram.TouchEvent) {
    const next = e.currentTarget.dataset.key as ContentTypeFilter | undefined;
    if (next === undefined || next === this.data.filter) {
      return;
    }
    this.resetFeedAndReload({ filter: next });
  },

  onHomeSceneTap(e: WechatMiniprogram.TouchEvent) {
    const next = e.currentTarget.dataset.key as RecommendScene | undefined;
    if (next === undefined || next === this.data.scene) {
      return;
    }
    this.resetFeedAndReload({ scene: next });
  },

  onSearchTap() {
    wx.navigateTo({ url: ROUTES.SEARCH });
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
    wx.navigateTo({ url: ROUTES.PUBLISH });
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
      const { filter, pageSize, scene } = this.data;
      const res = await getRecommendFeed(
        buildRecommendQuery(filter, undefined, 0, pageSize, scene),
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
        lastScore: page.nextLastScore,
        offset: page.nextOffset,
        finished,
      });
    } catch {
      // silent refresh failure is intentionally ignored
    }
  },

  syncTabBarState() {
    syncCustomTabBarSelected(this, 0);
  },

  async loadFirstPage(opts?: { tabSwitch?: boolean }) {
    const wasRefreshing = this.data.refreshing;
    const wasSwitching = opts?.tabSwitch === true || this.data.switching;
    const token = ++homeFeedRequestToken;

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
      const { filter, pageSize, scene } = this.data;
      const res = await getRecommendFeed(
        buildRecommendQuery(filter, undefined, 0, pageSize, scene),
      );

      if (token !== homeFeedRequestToken) {
        return;
      }

      if (!res.ok) {
        if (res.errorType === 'unauthorized' && !wasRefreshing) {
          this.setData({
            ...feedSwitchDone(),
            loading: false,
            list: [],
            ...feedFullScreenError(res),
          });
          return;
        }
        if (wasRefreshing) {
          wx.showToast({ title: '刷新失败', icon: 'none' });
        } else if (feedSwitchFailUsesToast(wasRefreshing, wasSwitching)) {
          this.setData(feedSwitchDone());
          wx.showToast({ title: '加载失败', icon: 'none' });
        } else {
          this.setData({
            ...feedSwitchDone(),
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
          lastScore: undefined,
          offset: 0,
          loadMoreError: false,
          ...feedFullScreenError({
            ok: false,
            errorType: 'invalidData',
            message: '暂无可展示的有效内容',
          }),
        });
        return;
      }

      const finished = resolveFinished(cards, pageSize, page.hasMore);
      const copy = emptyCopy(filter, this.data.scene);

      this.setData({
        list: cards,
        lastScore: page.nextLastScore,
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
      });
    } catch {
      if (token !== homeFeedRequestToken) {
        return;
      }
      if (wasRefreshing) {
        wx.showToast({ title: '刷新失败', icon: 'none' });
      } else if (feedSwitchFailUsesToast(wasRefreshing, wasSwitching)) {
        this.setData(feedSwitchDone());
        wx.showToast({ title: '加载失败', icon: 'none' });
      } else {
        this.setData({
          ...feedSwitchDone(),
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
      const { filter, scene, pageSize, lastScore, offset, list } = this.data;
      const res = await getRecommendFeed(
        buildRecommendQuery(filter, lastScore, offset, pageSize, scene),
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
        lastScore: page.nextLastScore,
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
