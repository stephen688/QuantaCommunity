import { CONTENT_TYPE_ALL, type ContentTypeFilter } from '../constants/content';
import { DEFAULT_PAGE_SIZE, USE_MOCK } from '../constants/request';
import { getFollowMockPool } from '../mock/follow-pool';
import { mockDelay } from '../mock/delay';
import { sliceMockScrollResult } from '../mock/scroll-slice';
import type { ScrollResultVO } from '../types/api';
import type { ContentVO } from '../types/content';
import type { FollowFeedQuery } from '../types/feed';
import type { FollowUserResult } from '../types/user';
import { markFollowFeedStale } from '../utils/follow-feed-bus';
import { request, type RequestResult } from '../utils/request';

/** Mock：关注状态覆盖（undefined 表示未覆盖，走默认规则） */
const mockFollowOverride = new Map<number, boolean>();
/** Mock：相对初始粉丝数的会话内增量 */
const mockFollowerAdjust = new Map<number, number>();

function defaultMockFollowed(targetUserId: number): boolean {
  return targetUserId % 3 !== 0;
}

export function getMockFollowedForUser(targetUserId: number): boolean {
  if (mockFollowOverride.has(targetUserId)) {
    return mockFollowOverride.get(targetUserId)!;
  }
  return defaultMockFollowed(targetUserId);
}

export function getMockFollowerAdjust(targetUserId: number): number {
  return mockFollowerAdjust.get(targetUserId) ?? 0;
}

export interface FollowFeedPage {
  list: ContentVO[];
  /** 后端明确 true/false；未返回时由 resolveFinished 结合条数判断 */
  hasMore?: boolean;
  /** 下一页请求的 lastId（时间戳游标） */
  nextLastId?: number;
  nextOffset: number;
}

export function buildFollowFeedQuery(
  filter: ContentTypeFilter,
  cursor: number | string | undefined,
  offset: number,
  pageSize: number = DEFAULT_PAGE_SIZE,
): FollowFeedQuery {
  const q: FollowFeedQuery = {
    pageSize,
    offset: offset < 0 ? 0 : offset,
  };
  if (filter !== CONTENT_TYPE_ALL) {
    q.contentType = filter;
  }
  if (cursor !== undefined && cursor !== null && cursor !== '') {
    const n = typeof cursor === 'string' ? Number(cursor) : cursor;
    if (!Number.isNaN(n)) {
      q.lastId = n;
    }
  }
  return q;
}

function compactFollowQuery(q: FollowFeedQuery): Record<string, string | number> {
  const out: Record<string, string | number> = {
    pageSize: q.pageSize,
    offset: q.offset ?? 0,
  };
  if (q.contentType !== undefined) {
    out.contentType = q.contentType;
  }
  if (q.lastId !== undefined && q.lastId !== '') {
    const raw = typeof q.lastId === 'string' ? Number(q.lastId) : q.lastId;
    if (!Number.isNaN(raw)) {
      out.lastId = raw;
    }
  }
  return out;
}

export async function getFollowFeed(
  query: FollowFeedQuery,
): Promise<RequestResult<FollowFeedPage>> {
  if (USE_MOCK) {
    await mockDelay();
    const pool = getFollowMockPool(query.contentType);
    const scroll = sliceMockScrollResult(pool, query.offset ?? 0, query.pageSize);
    if (!scroll.list || !Array.isArray(scroll.list)) {
      return { ok: false, errorType: 'invalidData', message: '关注动态数据格式异常' };
    }
    const hasMore: boolean | undefined =
      scroll.hasMore === true ? true : scroll.hasMore === false ? false : undefined;
    const nextLast =
      scroll.minScore === null || scroll.minScore === undefined
        ? undefined
        : Number(scroll.minScore);
    const nextOffset =
      scroll.offset === null || scroll.offset === undefined ? 0 : Number(scroll.offset);
    return {
      ok: true,
      data: {
        list: scroll.list,
        hasMore,
        nextLastId: Number.isNaN(nextLast as number) ? undefined : nextLast,
        nextOffset: Number.isNaN(nextOffset) ? 0 : nextOffset,
      },
    };
  }

  const res = await request<ScrollResultVO<ContentVO>>({
    method: 'GET',
    url: '/follow/feed',
    data: compactFollowQuery(query) as Record<string, unknown>,
  });
  if (!res.ok) {
    return res;
  }
  const scroll = res.data;
  if (!scroll || !Array.isArray(scroll.list)) {
    return { ok: false, errorType: 'invalidData', message: '关注动态数据格式异常' };
  }
  const hasMore: boolean | undefined =
    scroll.hasMore === true ? true : scroll.hasMore === false ? false : undefined;
  const nextLast =
    scroll.minScore === null || scroll.minScore === undefined
      ? undefined
      : Number(scroll.minScore);
  const nextOffset =
    scroll.offset === null || scroll.offset === undefined ? 0 : Number(scroll.offset);
  return {
    ok: true,
    data: {
      list: scroll.list,
      hasMore,
      nextLastId: Number.isNaN(nextLast as number) ? undefined : nextLast,
      nextOffset: Number.isNaN(nextOffset) ? 0 : nextOffset,
    },
  };
}

/** 关注 / 取关用户 */
export async function setFollowUser(userId: number, followed: boolean): Promise<RequestResult<FollowUserResult>> {
  if (!Number.isFinite(userId) || userId <= 0) {
    return Promise.resolve({
      ok: false,
      errorType: 'invalidData',
      message: '用户不存在或参数无效',
    });
  }
  if (typeof followed !== 'boolean') {
    return Promise.resolve({
      ok: false,
      errorType: 'invalidData',
      message: '关注状态无效',
    });
  }
  if (USE_MOCK) {
    await mockDelay();
    const cur = getMockFollowedForUser(userId);
    const next = followed;
    mockFollowOverride.set(userId, next);
    let delta = 0;
    if (next && !cur) {
      delta = 1;
    } else if (!next && cur) {
      delta = -1;
    }
    mockFollowerAdjust.set(userId, getMockFollowerAdjust(userId) + delta);
    const followerBase = 8 + (userId % 89);
    const fc = Math.max(0, followerBase + getMockFollowerAdjust(userId));
    markFollowFeedStale();
    return {
      ok: true,
      data: { isFollowed: next, followCount: fc, followerCount: fc },
    };
  }
  const res = await request<{ isFollowed?: boolean; followCount?: number }>({
    method: 'POST',
    url: `/follow/${userId}`,
    data: { followed },
  });
  if (!res.ok) {
    return res;
  }
  const raw = res.data;
  const isFollowed = raw?.isFollowed === true;
  const fc = raw?.followCount === undefined || raw?.followCount === null ? undefined : Number(raw.followCount);
  const out: FollowUserResult = {
    isFollowed,
    followCount: Number.isFinite(fc as number) ? (fc as number) : undefined,
    followerCount: Number.isFinite(fc as number) ? (fc as number) : undefined,
  };
  markFollowFeedStale();
  return { ok: true, data: out };
}
