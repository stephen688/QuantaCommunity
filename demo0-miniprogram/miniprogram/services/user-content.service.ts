import { DEFAULT_PAGE_SIZE, USE_MOCK } from '../constants/request';
import { mockDelay } from '../mock/delay';
import { getRecommendMockPool } from '../mock/recommend-pool';
import type { PageVO } from '../types/api';
import type { ContentCardModel, ContentVO } from '../types/content';
import { request, type RequestResult } from '../utils/request';
import { mapContentVOToCard } from './content.service';

export type MyContentAuditParam = 'PENDING' | 'APPROVED' | 'REJECTED' | '';

function inferHasMore(page: PageVO<ContentVO>, fetchedLen: number): boolean {
  if (page.hasMore === true) {
    return true;
  }
  if (page.hasMore === false) {
    return false;
  }
  const ps = page.pageSize ?? DEFAULT_PAGE_SIZE;
  const pn = page.pageNum ?? 1;
  if (typeof page.total === 'number' && page.total >= 0) {
    if (page.total === 0) {
      return false;
    }
    const totalPages =
      page.totalPage !== undefined && page.totalPage !== null
        ? page.totalPage
        : page.total > 0
          ? Math.ceil(page.total / ps)
          : 0;
    if (totalPages > 0) {
      return pn < totalPages;
    }
  }
  return fetchedLen >= ps;
}

function mapPageToCards(page: PageVO<ContentVO>): {
  list: ContentCardModel[];
  hasMore: boolean;
} {
  const raw = page.list ?? [];
  const list: ContentCardModel[] = [];
  for (const vo of raw) {
    const c = mapContentVOToCard(vo);
    if (c) {
      if (USE_MOCK && (c.auditStatus === undefined || Number.isNaN(c.auditStatus))) {
        const mod = c.contentId % 4;
        c.auditStatus = mod === 0 ? 0 : mod === 1 ? 1 : mod === 2 ? 2 : 1;
      }
      list.push(c);
    }
  }
  return { list, hasMore: inferHasMore(page, raw.length) };
}

function mockContentPage(current: number, size: number): PageVO<ContentVO> {
  const pool = getRecommendMockPool();
  const start = (current - 1) * size;
  const list = pool.slice(start, start + size);
  const total = pool.length;
  const totalPage = total > 0 ? Math.ceil(total / size) : 0;
  const hasMore = current < totalPage;
  return {
    list,
    total,
    pageNum: current,
    pageSize: size,
    totalPage,
    hasMore,
  };
}

async function fetchMyContentPage(
  current: number,
  size: number,
  auditStatus?: MyContentAuditParam,
): Promise<RequestResult<PageVO<ContentVO>>> {
  if (USE_MOCK) {
    await mockDelay();
    return { ok: true, data: mockContentPage(current, size) };
  }
  const c = current < 1 ? 1 : current;
  const s = size < 1 ? DEFAULT_PAGE_SIZE : size;
  const data: Record<string, unknown> = { current: c, size: s };
  if (auditStatus) {
    data.auditStatus = auditStatus;
  }
  return request<PageVO<ContentVO>>({
    method: 'GET',
    url: '/user/content/my/list',
    data,
  });
}

async function fetchPagedContent(
  path: string,
  current: number,
  size: number,
): Promise<RequestResult<PageVO<ContentVO>>> {
  if (USE_MOCK) {
    await mockDelay();
    return { ok: true, data: mockContentPage(current, size) };
  }
  const c = current < 1 ? 1 : current;
  const s = size < 1 ? DEFAULT_PAGE_SIZE : size;
  return request<PageVO<ContentVO>>({
    method: 'GET',
    url: path,
    data: { current: c, size: s },
  });
}

export async function getMyContentList(
  current: number,
  size: number = DEFAULT_PAGE_SIZE,
  auditStatus?: MyContentAuditParam,
): Promise<RequestResult<{ list: ContentCardModel[]; hasMore: boolean }>> {
  const res = await fetchMyContentPage(current, size, auditStatus);
  if (!res.ok) {
    return res;
  }
  const mapped = mapPageToCards(res.data);
  return { ok: true, data: mapped };
}

export async function getMyLikedList(
  current: number,
  size: number = DEFAULT_PAGE_SIZE,
): Promise<RequestResult<{ list: ContentCardModel[]; hasMore: boolean }>> {
  const res = await fetchPagedContent('/user/content/my/liked', current, size);
  if (!res.ok) {
    return res;
  }
  return { ok: true, data: mapPageToCards(res.data) };
}

export async function getMyCollectList(
  current: number,
  size: number = DEFAULT_PAGE_SIZE,
): Promise<RequestResult<{ list: ContentCardModel[]; hasMore: boolean }>> {
  const res = await fetchPagedContent('/user/content/my/collect', current, size);
  if (!res.ok) {
    return res;
  }
  return { ok: true, data: mapPageToCards(res.data) };
}

export async function getBrowseHistoryList(
  current: number,
  size: number = DEFAULT_PAGE_SIZE,
): Promise<RequestResult<{ list: ContentCardModel[]; hasMore: boolean }>> {
  const res = await fetchPagedContent('/user/content/my/browseHistory', current, size);
  if (!res.ok) {
    return res;
  }
  return { ok: true, data: mapPageToCards(res.data) };
}

export async function clearBrowseHistory(): Promise<RequestResult<void>> {
  if (USE_MOCK) {
    await mockDelay();
    return { ok: true, data: undefined as void };
  }
  return request<void>({
    method: 'DELETE',
    url: '/user/browse/history/clear',
  });
}
