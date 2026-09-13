import {
  CONTENT_TYPE_ALL,
  CONTENT_TYPE_LIFE,
  CONTENT_TYPE_PROFESSIONAL,
  type ContentTypeFilter,
} from '../../constants/content';
import {
  buildRagSearchRequest,
  mapRagResponseToAiSummary,
  mapRagResponseToCards,
  ragSearchContent,
} from '../../services/rag.service';
import type { ApiErrorType } from '../../types/api';
import type { ContentCardModel } from '../../types/content';
import { feedFullScreenError } from '../../utils/feed-error';
import { navigateToUserProfileFromEvent } from '../../utils/user-profile-nav';
import { patchContentCardInList } from '../../utils/content-card-list';
import { navigateFromContentCardEvent } from '../../utils/content-card-navigate';

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

function filterLabel(filter: ContentTypeFilter): string {
  if (filter === CONTENT_TYPE_LIFE) {
    return '生活';
  }
  if (filter === CONTENT_TYPE_PROFESSIONAL) {
    return '专业';
  }
  return '全部';
}

Page({
  data: {
    keyword: '',
    filter: CONTENT_TYPE_ALL as ContentTypeFilter,
    filterLabel: '全部',
    summary: '',
    emptyHint: '',
    sourceList: [] as ContentCardModel[],
    loading: true,
    errorType: '' as ApiErrorType | '',
    errorMessage: '',
    stateTitle: '',
    stateActionText: '',
  },

  onLoad(query: Record<string, string | undefined>) {
    const keyword = safeDecodeKeyword(query.keyword ?? '');
    const filter = parseFilterFromQuery(query);
    if (!keyword) {
      this.setData({
        loading: false,
        ...feedFullScreenError({
          ok: false,
          errorType: 'invalidData',
          message: '搜索关键词无效',
        }),
      });
      return;
    }
    this.setData({
      keyword,
      filter,
      filterLabel: filterLabel(filter),
    });
    void this.load();
  },

  onStateAction() {
    void this.load();
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
      sourceList: patchContentCardInList(this.data.sourceList, next),
    });
  },

  async load() {
    const { keyword, filter } = this.data;
    this.setData({
      loading: true,
      errorType: '',
      errorMessage: '',
      stateTitle: '',
      stateActionText: '',
      summary: '',
      emptyHint: '',
      sourceList: [],
    });
    try {
      const res = await ragSearchContent(buildRagSearchRequest(keyword, filter, true));
      if (!res.ok) {
        this.setData({
          loading: false,
          ...feedFullScreenError(res),
        });
        return;
      }
      const ai = mapRagResponseToAiSummary(res.data, keyword, filter);
      const sourceList = mapRagResponseToCards(res.data);
      const summary = (ai.summary || '').trim();
      this.setData({
        loading: false,
        summary,
        sourceList,
        emptyHint: ai.errorMessage || '',
        errorType: '',
        errorMessage: '',
        stateTitle: '',
        stateActionText: '',
      });
    } catch {
      this.setData({
        loading: false,
        ...feedFullScreenError({
          ok: false,
          errorType: 'network',
          message: '请求异常，请稍后重试',
        }),
      });
    }
  },
});
