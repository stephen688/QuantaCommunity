import type { ContentTypeFilter } from '../constants/content';
import type { FeedState } from '../types/feed';

export function createInitialFeedState<T>(filter: ContentTypeFilter): FeedState<T> {
  return {
    list: [],
    loading: false,
    refreshing: false,
    loadingMore: false,
    finished: false,
    filter,
    cursor: undefined,
    offset: 0,
  };
}

export function resetFeedState<T>(
  _prev: FeedState<T>,
  filter: ContentTypeFilter,
): FeedState<T> {
  return createInitialFeedState<T>(filter);
}

export function mergeFeedList<T extends { contentId: number }>(
  prevList: T[],
  nextList: T[],
): T[] {
  const seen = new Set(prevList.map((i) => i.contentId));
  const out = [...prevList];
  for (const item of nextList) {
    if (!seen.has(item.contentId)) {
      seen.add(item.contentId);
      out.push(item);
    }
  }
  return out;
}

/**
 * 根据接口返回的 hasMore 与当前页条数判断是否已无更多数据。
 */
export function resolveFinished<T>(
  list: T[],
  pageSize: number,
  hasMore: boolean | undefined,
): boolean {
  const len = list.length;
  if (hasMore === false) {
    return true;
  }
  if (hasMore === true) {
    return false;
  }
  return len < pageSize;
}
