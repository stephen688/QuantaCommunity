import {
  CONTENT_TYPE_ALL,
  CONTENT_TYPE_LIFE,
  CONTENT_TYPE_PROFESSIONAL,
  type ContentTypeFilter,
} from '../constants/content';
import { DEFAULT_PAGE_SIZE, USE_MOCK } from '../constants/request';
import { mockDelay } from '../mock/delay';
import { getRecommendMockPool } from '../mock/recommend-pool';
import type { PageVO } from '../types/api';
import type { ContentCardModel, ContentVO } from '../types/content';
import type {
  SearchHotAlumni,
  SearchHotQuestion,
  SearchHotRecommendData,
  SearchQuery,
  SearchSortType,
  SearchTrendingVO,
} from '../types/search';
import { request, type RequestResult } from '../utils/request';
import { mapContentVOToCard } from './content.service';

/** Mock 模式下内存维护搜索关键词历史（与真实接口行为一致，不落盘） */
let mockSearchKeywordHistory: string[] = [];

const MOCK_HISTORY_MAX = 30;

function normalizeKeywordForMatch(s: string): string {
  return s.trim().toLowerCase();
}

function mockPoolForFilter(filter: ContentTypeFilter): ContentVO[] {
  if (filter === CONTENT_TYPE_LIFE) {
    return getRecommendMockPool(CONTENT_TYPE_LIFE);
  }
  if (filter === CONTENT_TYPE_PROFESSIONAL) {
    return getRecommendMockPool(CONTENT_TYPE_PROFESSIONAL);
  }
  return getRecommendMockPool();
}

function filterMockSearchResults(pool: ContentVO[], keyword: string): ContentVO[] {
  const k = normalizeKeywordForMatch(keyword);
  if (!k) {
    return [];
  }
  return pool.filter((vo) => {
    const idStr = String(vo.contentId ?? '');
    const title = normalizeKeywordForMatch(vo.title || '');
    const content = normalizeKeywordForMatch(vo.content || '');
    const nick = normalizeKeywordForMatch(vo.nickName || '');
    return (
      idStr.includes(keyword.trim()) ||
      title.includes(k) ||
      content.includes(k) ||
      nick.includes(k)
    );
  });
}

function pushMockHistoryKeyword(keyword: string): void {
  const k = keyword.trim();
  if (!k) {
    return;
  }
  mockSearchKeywordHistory = [k, ...mockSearchKeywordHistory.filter((x) => x !== k)].slice(
    0,
    MOCK_HISTORY_MAX,
  );
}

/**
 * Mock：与关键词搜索相同过滤逻辑，供 RAG Mock 返回「相关帖子」摘要列表。
 */
export function getMockMatchedContentVOs(keyword: string, filter: ContentTypeFilter): ContentVO[] {
  const pool = mockPoolForFilter(filter);
  return filterMockSearchResults(pool, keyword);
}

export function buildSearchQuery(
  keyword: string,
  filter: ContentTypeFilter,
  current: number,
  pageSize: number = DEFAULT_PAGE_SIZE,
  sortType: SearchSortType = 'new',
): SearchQuery {
  const q: SearchQuery = {
    keyword: keyword.trim(),
    current: current < 1 ? 1 : current,
    pageSize: pageSize < 1 ? DEFAULT_PAGE_SIZE : pageSize,
    sortType,
  };
  if (filter === CONTENT_TYPE_LIFE) {
    q.contentType = CONTENT_TYPE_LIFE;
  } else if (filter === CONTENT_TYPE_PROFESSIONAL) {
    q.contentType = CONTENT_TYPE_PROFESSIONAL;
  }
  return q;
}

export function mapSearchPageToCards(page: PageVO<ContentVO>): {
  list: ContentCardModel[];
  hasMore: boolean;
  total: number;
} {
  const raw = page.list ?? [];
  const list: ContentCardModel[] = [];
  for (const vo of raw) {
    const card = mapContentVOToCard(vo);
    if (card) {
      list.push(card);
    }
  }
  const total = page.total === undefined || page.total === null ? list.length : Number(page.total);
  const hasMore = page.hasMore === true;
  return { list, hasMore, total: Number.isFinite(total) ? total : list.length };
}

export async function searchContent(
  query: SearchQuery,
): Promise<RequestResult<PageVO<ContentVO>>> {
  if (!query.keyword || !query.keyword.trim()) {
    return { ok: false, errorType: 'invalidData', message: '请输入搜索关键词' };
  }
  if (USE_MOCK) {
    await mockDelay();
    const filter: ContentTypeFilter =
      query.contentType === CONTENT_TYPE_LIFE
        ? CONTENT_TYPE_LIFE
        : query.contentType === CONTENT_TYPE_PROFESSIONAL
          ? CONTENT_TYPE_PROFESSIONAL
          : CONTENT_TYPE_ALL;
    let matched = getMockMatchedContentVOs(query.keyword, filter);
    if (query.sortType === 'hot') {
      matched = [...matched].sort((a, b) => (b.liked ?? 0) - (a.liked ?? 0));
    }
    const pageSize = query.pageSize < 1 ? DEFAULT_PAGE_SIZE : query.pageSize;
    const current = query.current < 1 ? 1 : query.current;
    const start = (current - 1) * pageSize;
    const slice = matched.slice(start, start + pageSize);
    const hasMore = start + slice.length < matched.length;
    pushMockHistoryKeyword(query.keyword.trim());
    return {
      ok: true,
      data: {
        list: slice,
        total: matched.length,
        hasMore,
        pageNum: current,
        pageSize,
      },
    };
  }
  const data: Record<string, string | number> = {
    keyword: query.keyword.trim(),
    current: query.current,
    pageSize: query.pageSize,
    sortType: query.sortType ?? 'new',
  };
  if (query.contentType !== undefined) {
    data.contentType = query.contentType;
  }
  return request<PageVO<ContentVO>>({
    method: 'GET',
    url: '/search/content',
    data: data as Record<string, unknown>,
  });
}

const MOCK_HOT_KEYWORDS = ['露营', 'Spring Boot', '小程序分包', '校友聚会', '考研复习'];

function buildMockHotRecommend(): SearchHotRecommendData {
  const pool = mockPoolForFilter(CONTENT_TYPE_ALL);
  const hotPool = [...pool].sort((a, b) => (b.liked ?? 0) - (a.liked ?? 0));
  const questions: SearchHotQuestion[] = hotPool.slice(0, 3).map((vo) => {
    const ct = Number(vo.contentType);
    return {
      contentId: Number(vo.contentId),
      title: String(vo.title || '').trim() || '热门问题',
      contentType:
        ct === CONTENT_TYPE_LIFE || ct === CONTENT_TYPE_PROFESSIONAL ? ct : undefined,
      heatLabel: `${vo.liked ?? 0} 赞`,
    };
  });
  const seenUsers = new Set<number>();
  const alumni: SearchHotAlumni[] = [];
  const deptLabels = ['计算机学院 · 18 届', '经管学院 · 19 届', '外语学院 · 17 届', '土木学院 · 20 届'];
  for (const vo of hotPool) {
    const uid = Number(vo.publishUserId);
    if (!Number.isFinite(uid) || uid <= 0 || seenUsers.has(uid)) {
      continue;
    }
    seenUsers.add(uid);
    const nickName = String(vo.nickName || `校友${uid}`);
    alumni.push({
      userId: uid,
      nickName,
      avatarUrl: String(vo.avatarUrl || ''),
      subtitle: deptLabels[alumni.length % deptLabels.length],
      avatarInitial: nickName.charAt(0) || '友',
    });
    if (alumni.length >= 6) {
      break;
    }
  }
  return {
    keywords: [...MOCK_HOT_KEYWORDS],
    questions,
    alumni,
  };
}

function isEmptyHotRecommend(data: SearchHotRecommendData | undefined | null): boolean {
  if (!data) {
    return true;
  }
  return (
    !data.keywords?.length && !data.questions?.length && !data.alumni?.length
  );
}

function mapTrendingToRecommend(vo: SearchTrendingVO): SearchHotRecommendData {
  const keywords = vo.hotKeywords ?? [];
  const questions = (vo.hotQuestions ?? [])
    .map((q): SearchHotQuestion | null => {
      const contentId = Number(q.contentId);
      if (!Number.isFinite(contentId) || contentId <= 0) {
        return null;
      }
      const title = String(q.title ?? '').trim() || '热门问题';
      const liked = q.liked;
      return {
        contentId,
        title,
        heatLabel: liked === undefined || liked === null ? undefined : `${liked} 赞`,
      };
    })
    .filter((q): q is SearchHotQuestion => q !== null);

  const alumni = (vo.hotAlumni ?? [])
    .map((a): SearchHotAlumni | null => {
      const userId = Number(a.userId);
      if (!Number.isFinite(userId) || userId <= 0) {
        return null;
      }
      const nickName = String(a.nickName ?? '').trim() || '校友';
      return {
        userId,
        nickName,
        avatarUrl: String(a.avatarUrl ?? ''),
        avatarInitial: nickName.charAt(0) || '友',
      };
    })
    .filter((a): a is SearchHotAlumni => a !== null);

  return { keywords, questions, alumni };
}

/** 搜索页热门发现（对接 GET /search/trending） */
export async function getSearchTrending(): Promise<RequestResult<SearchHotRecommendData>> {
  if (USE_MOCK) {
    await mockDelay();
    return { ok: true, data: buildMockHotRecommend() };
  }
  const res = await request<SearchTrendingVO>({
    method: 'GET',
    url: '/search/trending',
  });
  if (!res.ok) {
    return res;
  }
  const mapped = mapTrendingToRecommend(res.data ?? {});
  if (isEmptyHotRecommend(mapped)) {
    return { ok: true, data: { keywords: [], questions: [], alumni: [] } };
  }
  return { ok: true, data: mapped };
}

/** @deprecated 使用 getSearchTrending */
export async function getSearchHotRecommend(): Promise<RequestResult<SearchHotRecommendData>> {
  return getSearchTrending();
}

export async function getSearchHistoryKeywords(): Promise<RequestResult<string[]>> {
  if (USE_MOCK) {
    await mockDelay();
    return { ok: true, data: [...mockSearchKeywordHistory] };
  }
  return request<string[]>({
    method: 'GET',
    url: '/search/history/keywords',
  });
}

export async function clearSearchHistory(): Promise<RequestResult<void>> {
  if (USE_MOCK) {
    await mockDelay();
    mockSearchKeywordHistory = [];
    return { ok: true, data: undefined as void };
  }
  return request<void>({
    method: 'DELETE',
    url: '/search/history/clear',
  });
}

export function deleteSearchHistoryOne(id: number): Promise<RequestResult<void>> {
  if (!Number.isFinite(id) || id <= 0) {
    return Promise.resolve({
      ok: false,
      errorType: 'invalidData',
      message: '历史记录无效',
    });
  }
  if (USE_MOCK) {
    return (async () => {
      await mockDelay();
      return { ok: true, data: undefined as void };
    })();
  }
  return request<void>({
    method: 'DELETE',
    url: `/search/history/deleteOne/${id}`,
  });
}
