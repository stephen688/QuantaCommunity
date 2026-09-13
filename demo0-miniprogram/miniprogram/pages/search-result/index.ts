import {
  CONTENT_TYPE_ALL,
  CONTENT_TYPE_LIFE,
  CONTENT_TYPE_PROFESSIONAL,
  type ContentTypeFilter,
} from '../../constants/content';
import { DEFAULT_PAGE_SIZE } from '../../constants/request';
import { ROUTES } from '../../constants/route';
import {
  aiSummaryLoading,
  buildRagSearchRequest,
  mapRagResponseToAiSummary,
  ragSearchContent,
} from '../../services/rag.service';
import {
  buildSearchQuery,
  mapSearchPageToCards,
  searchContent,
} from '../../services/search.service';
import type { ApiErrorType } from '../../types/api';
import type { ContentCardModel } from '../../types/content';
import type { AiSummaryModel } from '../../types/rag';
import { feedFullScreenError } from '../../utils/feed-error';
import {
  beginFeedTabSwitch,
  feedSwitchDone,
  feedSwitchFailUsesToast,
} from '../../utils/feed-switch';
import { navigateFromContentCardEvent } from '../../utils/content-card-navigate';
import { navigateToUserProfileFromEvent } from '../../utils/user-profile-nav';
import { patchContentCardInList } from '../../utils/content-card-list';

function safeDecodeKeyword(raw: string): string {
  const s = (raw || '').trim();
  if (!s) {
    return '';
  }
  try {
    return decodeURIComponent(s);
  } catch {
    return s;
  }
}

function parseFilterFromQuery(q: Record<string, string | undefined>): ContentTypeFilter {
  const raw = q.contentType;
  if (raw === '1' || raw === `${CONTENT_TYPE_LIFE}`) {
    return CONTENT_TYPE_LIFE;
  }
  if (raw === '2' || raw === `${CONTENT_TYPE_PROFESSIONAL}`) {
    return CONTENT_TYPE_PROFESSIONAL;
  }
  return CONTENT_TYPE_ALL;
}

function emptyAiSummary(keyword: string, filter: ContentTypeFilter): AiSummaryModel {
  return {
    keyword,
    filter,
    summary: '',
    sourceCount: 0,
    loading: false,
    errorType: '',
    errorMessage: '',
  };
}

let resultPageToken = 0;

Page({
  data: {
    invalidKeyword: false,
    keyword: '',
    filter: CONTENT_TYPE_ALL as ContentTypeFilter,
    list: [] as ContentCardModel[],
    switching: false,
    aiSummary: emptyAiSummary('', CONTENT_TYPE_ALL),
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
  },

  _loadMoreLock: false,

  onLoad(query: Record<string, string | undefined>) {
    const keyword = safeDecodeKeyword(query.keyword ?? '');
    const filter = parseFilterFromQuery(query);
    if (!keyword) {
      this.setData({
        invalidKeyword: true,
        loading: false,
        aiSummary: emptyAiSummary('', filter),
      });
      return;
    }
    this.setData({
      invalidKeyword: false,
      keyword,
      filter,
      aiSummary: aiSummaryLoading(keyword, filter),
    });
    void this.reloadAll(false, { filter });
  },

  onPullDownRefresh() {
    if (this.data.invalidKeyword) {
      wx.stopPullDownRefresh();
      return;
    }
    this.setData({ refreshing: true });
    void this.reloadAll(true, { filter: this.data.filter });
  },

  onReachBottom() {
    if (
      this.data.invalidKeyword ||
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

  onBackToSearch() {
    wx.navigateBack();
  },

  onFilterChange(e: WechatMiniprogram.CustomEvent<{ value: ContentTypeFilter }>) {
    const next = e.detail?.value;
    if (next === undefined || next === this.data.filter) {
      return;
    }
    wx.pageScrollTo({ scrollTop: 0, duration: 0 });
    const hadContent = this.data.list.length > 0;
    this.setData({
      filter: next,
      ...beginFeedTabSwitch(),
      ...(hadContent ? {} : { list: [] }),
    });
    void this.reloadAll(false, { filter: next, tabSwitch: true });
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

  onAiSummaryTap() {
    const { keyword, filter } = this.data;
    if (!keyword) {
      return;
    }
    const k = encodeURIComponent(keyword);
    let url = `${ROUTES.SEARCH_AI_DETAIL}?keyword=${k}`;
    if (filter === CONTENT_TYPE_LIFE) {
      url += '&contentType=1';
    } else if (filter === CONTENT_TYPE_PROFESSIONAL) {
      url += '&contentType=2';
    }
    wx.navigateTo({ url });
  },

  onStateAction() {
    void this.reloadAll(false, { filter: this.data.filter });
  },

  onFooterRetry() {
    this.setData({ loadMoreError: false });
    void this.loadMore();
  },

  async reloadAll(
    fromRefresh: boolean = false,
    opts?: { filter: ContentTypeFilter; tabSwitch?: boolean },
  ) {
    const token = ++resultPageToken;
    const filter = opts?.filter ?? this.data.filter;
    const keyword = this.data.keyword;
    const pageSize = this.data.pageSize;

    const wasSwitching = opts?.tabSwitch === true || this.data.switching;
    if (!fromRefresh && !wasSwitching) {
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
        aiSummary: aiSummaryLoading(keyword, filter),
      });
    } else if (wasSwitching) {
      this.setData({
        current: 1,
        finished: false,
        errorType: '',
        errorMessage: '',
        stateTitle: '',
        stateActionText: '',
        loadMoreError: false,
        aiSummary: aiSummaryLoading(keyword, filter),
      });
    } else {
      this.setData({
        errorType: '',
        errorMessage: '',
        stateTitle: '',
        stateActionText: '',
        loadMoreError: false,
        aiSummary: aiSummaryLoading(keyword, filter),
      });
    }

    void ragSearchContent(buildRagSearchRequest(keyword, filter, true)).then((res) => {
      if (token !== resultPageToken) {
        return;
      }
      if (!res.ok) {
        this.setData({
          aiSummary: {
            keyword,
            filter,
            summary: '',
            sourceCount: 0,
            loading: false,
            errorType: res.errorType,
            errorMessage: res.message,
          },
        });
        return;
      }
      this.setData({
        aiSummary: mapRagResponseToAiSummary(res.data, keyword, filter),
      });
    });

    try {
      const res = await searchContent(buildSearchQuery(keyword, filter, 1, pageSize));
      if (token !== resultPageToken) {
        return;
      }
      if (!res.ok) {
        if (feedSwitchFailUsesToast(fromRefresh, wasSwitching)) {
          this.setData(feedSwitchDone());
          wx.showToast({ title: fromRefresh ? '刷新失败' : '加载失败', icon: 'none' });
        } else {
          this.setData({
            ...feedSwitchDone(),
            ...feedFullScreenError(res),
          });
        }
        return;
      }
      const mapped = mapSearchPageToCards(res.data);
      const rawList = Array.isArray(res.data.list) ? res.data.list : [];
      if (rawList.length > 0 && mapped.list.length === 0) {
        const invalidRes = {
          ok: false as const,
          errorType: 'invalidData' as const,
          message: '暂无可展示的有效内容',
        };
        if (feedSwitchFailUsesToast(fromRefresh, wasSwitching)) {
          this.setData(feedSwitchDone());
          wx.showToast({ title: '暂无有效内容', icon: 'none' });
        } else {
          this.setData({
            list: [],
            current: 1,
            finished: true,
            ...feedSwitchDone(),
            ...feedFullScreenError(invalidRes),
          });
        }
        return;
      }
      this.setData({
        list: mapped.list,
        current: 1,
        finished: !mapped.hasMore || mapped.list.length < pageSize,
        ...feedSwitchDone(),
        errorType: '',
        errorMessage: '',
        stateTitle: '',
        stateActionText: '',
      });
    } catch {
      if (token !== resultPageToken) {
        return;
      }
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
      this.data.invalidKeyword ||
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
      const { keyword, filter, current, pageSize, list } = this.data;
      const nextCurrent = current + 1;
      const res = await searchContent(
        buildSearchQuery(keyword, filter, nextCurrent, pageSize),
      );
      if (!res.ok) {
        this.setData({ loadMoreError: true });
        return;
      }
      const mapped = mapSearchPageToCards(res.data);
      const merged = list.concat(
        mapped.list.filter((card) => !list.some((it) => it.contentId === card.contentId)),
      );
      this.setData({
        list: merged,
        current: nextCurrent,
        finished: !mapped.hasMore || mapped.list.length < pageSize,
      });
    } catch {
      this.setData({ loadMoreError: true });
    } finally {
      this._loadMoreLock = false;
      this.setData({ loadingMore: false });
    }
  },
});
