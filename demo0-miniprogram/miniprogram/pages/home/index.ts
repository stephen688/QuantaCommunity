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
  reportRecommendExposures,
} from '../../services/content.service';
import type { ApiErrorType } from '../../types/api';
import type { ContentCardModel } from '../../types/content';
import type { RecommendScene, RecommendationState } from '../../types/feed';
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
import { createExposureQueue } from '../../utils/recommend-exposure';
import { getRecommendVisitor, type RecommendVisitor } from '../../utils/recommend-visitor';
import { createSubmissionToken } from '../../utils/submission';

function isCardModel(item: ContentCardModel | null): item is ContentCardModel {
  return Boolean(item);
}

function emptyCopy(
  filter: ContentTypeFilter,
  scene: RecommendScene = 'recommend',
): { title: string; desc: string } {
  const isHot = scene === 'hot';
  if (filter === CONTENT_TYPE_LIFE) {
    return {
      title: isHot ? '暂无热门生活内容' : '暂无生活内容',
      desc: isHot ? '切换分区或下拉刷新试试' : '正在寻找适合你的生活内容',
    };
  }
  if (filter === CONTENT_TYPE_PROFESSIONAL) {
    return {
      title: isHot ? '暂无热门专业内容' : '暂无专业内容',
      desc: isHot ? '切换分区或下拉刷新试试' : '正在寻找适合你的专业内容',
    };
  }
  return {
    title: isHot ? '暂无热门推荐' : '暂无推荐',
    desc: isHot ? '切换分区或下拉刷新试试' : '下拉刷新或去发布一条内容',
  };
}

function isRecommendationScene(scene: RecommendScene): boolean {
  return scene === 'recommend' || scene === 'latest';
}

const RECOMMEND_PAGE_SIZE = 5;

function recommendationFinished(
  state: RecommendationState | undefined,
  hasMore: boolean | undefined,
  list: ContentCardModel[],
  pageSize: number,
  legacy: boolean,
): boolean {
  if (legacy) {
    return resolveFinished(list, pageSize, hasMore);
  }
  if (state === 'EXHAUSTED' || hasMore === false) {
    return true;
  }
  return false;
}

/** 防止快速切换分区时，较早发出的推荐请求覆盖较新结果 */
let homeFeedRequestToken = 0;

Page({
  data: {
    filter: CONTENT_TYPE_ALL as ContentTypeFilter,
    /** 推荐场景：recommend 推荐 / hot 热度（对应 GET /content/recommend?scene=） */
    scene: 'recommend' as RecommendScene,
    feedSceneTabs: [
      { key: 'recommend' as RecommendScene, label: '推荐' },
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
    emptyTitle: emptyCopy(CONTENT_TYPE_ALL, 'recommend').title,
    emptyDesc: emptyCopy(CONTENT_TYPE_ALL, 'recommend').desc,
    lastScore: undefined as number | undefined,
    offset: 0,
    pageSize: RECOMMEND_PAGE_SIZE,
    loadMoreError: false,
    recommendationState: 'READY' as RecommendationState,
    canRevisit: false,
  },

  _loadMoreLock: false,
  _recommendSessionId: undefined as string | undefined,
  _recommendCursor: undefined as string | undefined,
  _recommendRevisitOfSessionId: undefined as string | undefined,
  _recommendVisitor: undefined as RecommendVisitor | undefined,
  _recommendLegacyResponse: false,
  _recommendSearchAttempts: 0,
  _recommendGeneration: 0,
  _feedVisibilityGeneration: 0,
  _observationEnabled: false,
  _intersectionObserver: undefined as WechatMiniprogram.IntersectionObserver | undefined,
  _exposureQueue: undefined as ReturnType<typeof createExposureQueue> | undefined,
  _pendingExposureFlush: undefined as
    | { actorKey: string; promise: Promise<void> }
    | undefined,

  onLoad() {
    void this.loadFirstPage();
  },

  onShow() {
    this.syncTabBarState();
    void refreshCustomTabBarUnread(this);
    if (isRecommendationScene(this.data.scene)) {
      stopFeedTimer();
      void this.checkRecommendationIdentity();
    } else {
      startFeedTimer(() => this.silentRefresh());
    }
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
    // 隐藏期间完成的旧请求不能回写当前页面，重新显示后只接受新一代响应。
    this._feedVisibilityGeneration += 1;
    this._observationEnabled = false;
    this.disconnectExposureObserver();
    void this._exposureQueue?.flush();
    stopFeedTimer();
  },

  onUnload() {
    this._feedVisibilityGeneration += 1;
    this._observationEnabled = false;
    this.disconnectExposureObserver();
    this._exposureQueue?.dispose();
    this._exposureQueue = undefined;
    this._pendingExposureFlush = undefined;
    stopFeedTimer();
  },

  onPullDownRefresh() {
    this.setData({ refreshing: true });
    void this.loadFirstPage({ newRecommendationSession: true });
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
    stopFeedTimer();
    const copy = emptyCopy(filter, scene);
    const hadContent = this.data.list.length > 0;
    this.invalidateRecommendationRuntime();
    this.setData({
      ...patch,
      filter,
      scene,
      pageSize: isRecommendationScene(scene) ? RECOMMEND_PAGE_SIZE : DEFAULT_PAGE_SIZE,
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
      recommendationState: 'READY',
      canRevisit: false,
    });
    if (scene === 'hot') {
      startFeedTimer(() => this.silentRefresh());
    }
    wx.pageScrollTo({ scrollTop: 0, duration: 0 });
    void this.loadFirstPage({ tabSwitch: true, newRecommendationSession: true });
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
    if (this.data.canRevisit && this.data.recommendationState === 'EXHAUSTED') {
      void this.startRecommendationRevisit();
      return;
    }
    void this.loadFirstPage();
  },

  onEmptyAction() {
    if (this.data.recommendationState === 'SEARCHING') {
      void this.loadMore();
      return;
    }
    if (this.data.canRevisit && this.data.recommendationState === 'EXHAUSTED') {
      void this.startRecommendationRevisit();
      return;
    }
    wx.navigateTo({ url: ROUTES.PUBLISH });
  },

  onRetryLoadMore() {
    this.setData({ loadMoreError: false });
    void this.loadMore();
  },

  invalidateRecommendationRuntime(options?: { preserveExposure?: boolean }) {
    this._observationEnabled = false;
    this._recommendGeneration += 1;
    this.disconnectExposureObserver();
    const previousExposureQueue = this._exposureQueue;
    this._exposureQueue = undefined;
    const preserveExposure = options?.preserveExposure !== false;
    if (!preserveExposure) {
      // 身份变化时，旧主体的曝光不能阻塞新主体，也不能混入新主体的批次。
      this._pendingExposureFlush = undefined;
    }
    if (previousExposureQueue) {
      if (!preserveExposure) {
        previousExposureQueue.dispose();
      } else {
        // 同一主体切换轮次时排空旧 session 的已收集曝光，不能让新 session
        // 的 GET 与旧批次 POST 并发。drain 只等待当前请求和连续批次，失败
        // 会保留待重试项并立即放行，不把失败伪装成已曝光。
        const actorKey = this._recommendVisitor?.actorKey;
        const flushPromise = previousExposureQueue.drain();
        if (actorKey) {
          const previous = this._pendingExposureFlush;
          const promise =
            previous?.actorKey === actorKey
              ? Promise.all([previous.promise, flushPromise]).then(() => undefined)
              : flushPromise;
          this._pendingExposureFlush = { actorKey, promise };
        }
      }
    }
    this._recommendSessionId = undefined;
    this._recommendCursor = undefined;
    this._recommendRevisitOfSessionId = undefined;
    this._recommendLegacyResponse = false;
    this._recommendSearchAttempts = 0;
  },

  async waitForRecommendationExposureFlush(actorKey: string): Promise<void> {
    const pending = this._pendingExposureFlush;
    if (!pending || pending.actorKey !== actorKey) {
      return;
    }
    await pending.promise;
    if (this._pendingExposureFlush?.promise === pending.promise) {
      this._pendingExposureFlush = undefined;
    }
  },

  async checkRecommendationIdentity() {
    if (!isRecommendationScene(this.data.scene)) {
      return;
    }
    try {
      const visitor = await getRecommendVisitor();
      if (
        this._recommendVisitor &&
        this._recommendVisitor.actorKey !== visitor.actorKey
      ) {
        homeFeedRequestToken += 1;
        this.invalidateRecommendationRuntime({ preserveExposure: false });
        // 身份变化后先绑定新主体，避免下一次建会话仍被旧主体拦截。
        this._recommendVisitor = visitor;
        this.setData({
          list: [],
          finished: false,
          lastScore: undefined,
          offset: 0,
          recommendationState: 'READY',
          canRevisit: false,
          errorType: '',
          errorMessage: '',
          loading: true,
        });
        void this.loadFirstPage({ newRecommendationSession: true });
        return;
      }
      this._recommendVisitor = visitor;
      if (
        this._recommendSessionId &&
        this.data.list.length > 0 &&
        !this.data.loading &&
        !this.data.switching &&
        !this._recommendLegacyResponse
      ) {
        this._observationEnabled = true;
        this.startExposureObserver();
      }
    } catch {
      // 首次加载会显示游客标识生成失败；回到首页时不打断现有列表。
    }
  },

  async ensureRecommendationSession(
    newSession = false,
    revisitOfSessionId?: string,
    guard?: {
      generation?: number;
      requestToken?: number;
      actorKey?: string;
    },
  ): Promise<{ sessionId: string; visitor: RecommendVisitor }> {
    const isStale = () =>
      (guard?.generation !== undefined && guard.generation !== this._recommendGeneration) ||
      (guard?.requestToken !== undefined && guard.requestToken !== homeFeedRequestToken);

    if (isStale()) {
      throw new Error('recommendation request superseded');
    }
    const visitor = await getRecommendVisitor();
    if (
      isStale() ||
      (guard?.actorKey !== undefined && guard.actorKey !== visitor.actorKey) ||
      (this._recommendVisitor && this._recommendVisitor.actorKey !== visitor.actorKey)
    ) {
      throw new Error('recommendation identity changed');
    }
    this._recommendVisitor = visitor;
    if (!newSession && this._recommendSessionId) {
      return { sessionId: this._recommendSessionId, visitor };
    }
    const sessionId = await createSubmissionToken();
    if (
      isStale() ||
      this._recommendVisitor?.actorKey !== visitor.actorKey
    ) {
      throw new Error('recommendation request superseded');
    }
    this._recommendSessionId = sessionId;
    this._recommendCursor = undefined;
    this._recommendRevisitOfSessionId = revisitOfSessionId;
    this._recommendLegacyResponse = false;
    this._recommendSearchAttempts = 0;
    return { sessionId, visitor };
  },

  createRecommendationExposureQueue(visitor: RecommendVisitor) {
    this._exposureQueue?.dispose();
    let ownedQueue: ReturnType<typeof createExposureQueue> | undefined;
    ownedQueue = createExposureQueue(async (batch) => {
      if (
        !this._recommendVisitor ||
        this._recommendVisitor.actorKey !== visitor.actorKey
      ) {
        throw new Error('recommendation identity changed');
      }
      const currentVisitor = await getRecommendVisitor();
      if (currentVisitor.actorKey !== visitor.actorKey) {
        throw new Error('recommendation identity changed');
      }
      const res = await reportRecommendExposures(batch, currentVisitor);
      if (res.ok) {
        return;
      }
      if (res.errorType === 'recommendExpired') {
        if (this._exposureQueue === ownedQueue) {
          ownedQueue?.dispose();
          this._exposureQueue = undefined;
        }
      }
      throw new Error(res.message || '推荐曝光回传失败');
    });
    this._exposureQueue = ownedQueue;
  },

  disconnectExposureObserver() {
    this._intersectionObserver?.disconnect();
    this._intersectionObserver = undefined;
  },

  startExposureObserver() {
    if (
      !this._observationEnabled ||
      !isRecommendationScene(this.data.scene) ||
      this._recommendLegacyResponse ||
      !this._recommendSessionId ||
      !this._recommendVisitor ||
      !this.data.list.length
    ) {
      return;
    }
    this.disconnectExposureObserver();
    const sessionId = this._recommendSessionId;
    const actorKey = this._recommendVisitor.actorKey;
    const generation = this._recommendGeneration;
    wx.createSelectorQuery()
      .select('.home__header')
      .boundingClientRect()
      .exec((result) => {
        if (
          !this._observationEnabled ||
          generation !== this._recommendGeneration ||
          sessionId !== this._recommendSessionId
        ) {
          return;
        }
        const header = result?.[0] as { height?: number } | undefined;
        const topMargin = -Math.ceil(Number(header?.height) || 0);
        try {
          const observer = wx.createIntersectionObserver(this, {
            observeAll: true,
            thresholds: [0, 0.5, 1],
          });
          observer.relativeToViewport({ top: topMargin });
          observer.observe('.home__observed-card', (entry) => {
            if (
              !this._observationEnabled ||
              generation !== this._recommendGeneration ||
              sessionId !== this._recommendSessionId ||
              actorKey !== this._recommendVisitor?.actorKey
            ) {
              return;
            }
            const contentId = Number(
              (entry.dataset as Record<string, unknown> | undefined)?.contentId,
            );
            if (!Number.isFinite(contentId)) {
              return;
            }
            this._exposureQueue?.observe({
              sessionId,
              actorKey,
              contentId: Math.trunc(contentId),
              ratio: Number(entry.intersectionRatio),
            });
          });
          this._intersectionObserver = observer;
        } catch {
          // 低版本基础库没有观察器时，保持列表可用但不伪造曝光。
        }
      });
  },

  async startRecommendationRevisit() {
    const oldSessionId = this._recommendSessionId;
    if (!oldSessionId || !this.data.canRevisit) {
      return;
    }
    homeFeedRequestToken += 1;
    this.invalidateRecommendationRuntime();
    this.setData({
      loading: true,
      loadingMore: false,
      errorType: '',
      errorMessage: '',
      stateTitle: '',
      stateActionText: '',
      finished: false,
      lastScore: undefined,
      offset: 0,
      recommendationState: 'READY',
      canRevisit: false,
    });
    await this.loadFirstPage({
      newRecommendationSession: true,
      revisitOfSessionId: oldSessionId,
    });
  },

  buildHomeRecommendQuery(
    filter: ContentTypeFilter,
    pageSize: number,
    cursor?: string,
    offset?: number,
    useLegacyCursor = true,
  ) {
    const requestOffset = offset ?? this.data.offset;
    const legacyCursor =
      useLegacyCursor && this._recommendLegacyResponse ? this.data.lastScore : undefined;
    return buildRecommendQuery(
      filter,
      legacyCursor ?? cursor,
      requestOffset,
      pageSize,
      this.data.scene,
      this._recommendLegacyResponse
        ? {}
        : {
            feedSessionId: this._recommendSessionId,
            pageCursor: cursor,
            revisitOfSessionId: this._recommendRevisitOfSessionId,
          },
    );
  },

  async silentRefresh() {
    if (
      this.data.scene !== 'hot' ||
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
      const requestToken = homeFeedRequestToken;
      const requestScene = scene;
      const requestFilter = filter;
      const requestVisibilityGeneration = this._feedVisibilityGeneration;
      const res = await getRecommendFeed(
        buildRecommendQuery(filter, undefined, 0, pageSize, scene),
      );
      if (
        requestToken !== homeFeedRequestToken ||
        requestScene !== this.data.scene ||
        requestFilter !== this.data.filter ||
        requestVisibilityGeneration !== this._feedVisibilityGeneration
      ) {
        return;
      }
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

  async loadFirstPage(opts?: {
    tabSwitch?: boolean;
    newRecommendationSession?: boolean;
    revisitOfSessionId?: string;
  }) {
    const wasRefreshing = this.data.refreshing;
    const wasSwitching = opts?.tabSwitch === true || this.data.switching;
    const recommendation = isRecommendationScene(this.data.scene);
    if (recommendation && opts?.newRecommendationSession) {
      this.invalidateRecommendationRuntime();
    }
    const token = ++homeFeedRequestToken;
    const generation = this._recommendGeneration;

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
      let requestSessionId: string | undefined;
      let requestActorKey: string | undefined;
      let query;
      if (recommendation) {
        // Capture the actor before waiting so an identity change cannot attach
        // the old queue to the new session after the drain.
        const visitorBeforeFlush = await getRecommendVisitor();
        requestActorKey = visitorBeforeFlush.actorKey;
        if (
          token !== homeFeedRequestToken ||
          generation !== this._recommendGeneration ||
          (this._recommendVisitor &&
            this._recommendVisitor.actorKey !== requestActorKey)
        ) {
          return;
        }
        await this.waitForRecommendationExposureFlush(requestActorKey);
        if (
          token !== homeFeedRequestToken ||
          generation !== this._recommendGeneration ||
          (this._recommendVisitor &&
            requestActorKey !== this._recommendVisitor.actorKey)
        ) {
          return;
        }
        const session = await this.ensureRecommendationSession(
          opts?.newRecommendationSession === true,
          opts?.revisitOfSessionId,
          {
            generation,
            requestToken: token,
            actorKey: this._recommendVisitor?.actorKey,
          },
        );
        requestSessionId = session.sessionId;
        requestActorKey = session.visitor.actorKey;
        const currentVisitor = await getRecommendVisitor();
        if (
          token !== homeFeedRequestToken ||
          generation !== this._recommendGeneration ||
          requestActorKey !== this._recommendVisitor?.actorKey ||
          currentVisitor.actorKey !== requestActorKey
        ) {
          return;
        }
        query = this.buildHomeRecommendQuery(filter, pageSize, undefined, 0, false);
      } else {
        query = buildRecommendQuery(filter, undefined, 0, pageSize, scene);
      }
      const res = await getRecommendFeed(
        query,
        recommendation ? this._recommendVisitor : undefined,
      );

      if (
        token !== homeFeedRequestToken ||
        (recommendation &&
          (generation !== this._recommendGeneration ||
            requestSessionId !== this._recommendSessionId ||
            requestActorKey !== this._recommendVisitor?.actorKey))
      ) {
        return;
      }

      if (!res.ok) {
        if (recommendation && res.errorType === 'recommendExpired') {
          this.invalidateRecommendationRuntime();
          if (this.data.list.length > 0) {
            wx.showToast({ title: '推荐已更新，刷新继续', icon: 'none' });
            this.setData({
              ...feedSwitchDone(),
              loading: false,
              finished: false,
              recommendationState: 'READY',
              canRevisit: false,
            });
          } else {
            this.setData({
              ...feedSwitchDone(),
              loading: false,
              ...feedFullScreenError({
                ...res,
                errorType: 'server',
                message: '推荐已更新，刷新继续',
              }),
            });
          }
          return;
        }
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

      const legacy = recommendation && !page.feedSessionId && !page.recommendationState;
      if (
        recommendation &&
        !legacy &&
        page.feedSessionId &&
        requestSessionId &&
        page.feedSessionId !== requestSessionId
      ) {
        this.setData({
          ...feedSwitchDone(),
          loadMoreError: false,
          ...feedFullScreenError({
            ok: false,
            errorType: 'invalidData',
            message: '推荐会话响应已失效，请刷新',
          }),
        });
        return;
      }

      if (rawList.length > 0 && cards.length === 0 && (!recommendation || legacy)) {
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

      if (recommendation) {
        this._recommendLegacyResponse = legacy;
        this._recommendCursor = page.nextCursor;
        this._recommendSearchAttempts =
          page.recommendationState === 'SEARCHING' ? 1 : 0;
        if (!legacy && !this._exposureQueue && this._recommendVisitor) {
          this.createRecommendationExposureQueue(this._recommendVisitor);
        }
        this._observationEnabled = !legacy && cards.length > 0;
      }
      const finished = recommendation
        ? recommendationFinished(
            page.recommendationState,
            page.hasMore,
            cards,
            pageSize,
            legacy,
          )
        : resolveFinished(cards, pageSize, page.hasMore);
      const copy = emptyCopy(filter, this.data.scene);
      const recommendationState = recommendation
        ? page.recommendationState ?? 'READY'
        : this.data.recommendationState;
      const canRevisit = recommendation ? page.canRevisit === true : false;

      this.setData({
        list: cards,
        lastScore: page.nextLastScore,
        offset: page.nextOffset,
        finished,
        recommendationState,
        canRevisit,
        ...feedSwitchDone(),
        errorType: '',
        errorMessage: '',
        stateTitle: '',
        stateActionText: '',
        loadMoreError: false,
        emptyTitle: copy.title,
        emptyDesc:
          recommendationState === 'EXHAUSTED'
            ? '暂时没有新内容，点击下方按钮可以再看已看内容'
            : recommendationState === 'SEARCHING'
              ? '继续查找会沿用当前推荐会话'
              : copy.desc,
      });
      if (recommendation) {
        this.startExposureObserver();
        if (
          page.recommendationState === 'SEARCHING' &&
          cards.length === 0 &&
          this._recommendSearchAttempts < 2 &&
          this._recommendCursor
        ) {
          void this.loadMore();
        }
      }
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
      (!this.data.list.length &&
        !(isRecommendationScene(this.data.scene) &&
          this.data.recommendationState === 'SEARCHING'))
    ) {
      return;
    }

    this._loadMoreLock = true;
    this.setData({ loadingMore: true, loadMoreError: false });

    try {
      const { filter, scene, pageSize, lastScore, offset, list } = this.data;
      const recommendation = isRecommendationScene(scene);
      const generation = this._recommendGeneration;
      const requestSessionId = recommendation ? this._recommendSessionId : undefined;
      const requestCursor = recommendation ? this._recommendCursor : undefined;
      const requestActorKey = recommendation ? this._recommendVisitor?.actorKey : undefined;
      let query;
      if (recommendation) {
        if (!requestSessionId) {
          this.setData({ loadMoreError: true });
          return;
        }
        query = this.buildHomeRecommendQuery(filter, pageSize, requestCursor);
      } else {
        query = buildRecommendQuery(filter, lastScore, offset, pageSize, scene);
      }
      const requestToken = homeFeedRequestToken;
      const res = await getRecommendFeed(
        query,
        recommendation ? this._recommendVisitor : undefined,
      );

      if (
        requestToken !== homeFeedRequestToken ||
        (recommendation &&
          (generation !== this._recommendGeneration ||
            requestSessionId !== this._recommendSessionId ||
            requestCursor !== this._recommendCursor ||
            requestActorKey !== this._recommendVisitor?.actorKey))
      ) {
        return;
      }

      if (!res.ok) {
        if (recommendation && res.errorType === 'recommendExpired') {
          this.invalidateRecommendationRuntime();
          wx.showToast({ title: '推荐已更新，刷新继续', icon: 'none' });
          this.setData({
            loadMoreError: false,
            finished: false,
            recommendationState: 'READY',
            canRevisit: false,
          });
          return;
        }
        this.setData({ loadMoreError: true });
        return;
      }

      const page = res.data;
      const cards = page.list.map(mapContentVOToCard).filter(isCardModel);
      const merged = mergeFeedList(list, cards);
      const legacy = recommendation && !page.feedSessionId && !page.recommendationState;
      if (
        recommendation &&
        !legacy &&
        page.feedSessionId &&
        requestSessionId &&
        page.feedSessionId !== requestSessionId
      ) {
        this.setData({ loadMoreError: true });
        return;
      }
      if (recommendation) {
        this._recommendLegacyResponse = legacy;
        this._recommendCursor = page.nextCursor;
        this._recommendSearchAttempts =
          page.recommendationState === 'SEARCHING'
            ? this._recommendSearchAttempts + 1
            : 0;
      }
      const finished = recommendation
        ? recommendationFinished(
            page.recommendationState,
            page.hasMore,
            cards,
            pageSize,
            legacy,
          )
        : resolveFinished(cards, pageSize, page.hasMore);

      this.setData({
        list: merged,
        lastScore: page.nextLastScore,
        offset: page.nextOffset,
        finished,
        ...(recommendation
          ? {
              recommendationState: page.recommendationState ?? 'READY',
              canRevisit: page.canRevisit === true,
            }
          : {}),
      });
      if (recommendation && !legacy && cards.length > 0) {
        this._observationEnabled = true;
        this.startExposureObserver();
      }
    } catch {
      this.setData({ loadMoreError: true });
    } finally {
      this.setData({ loadingMore: false });
      this._loadMoreLock = false;
    }
  },

});
