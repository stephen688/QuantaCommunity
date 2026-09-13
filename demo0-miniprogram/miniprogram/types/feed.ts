import type { ContentTypeFilter } from '../constants/content';
import type { ApiErrorType } from './api';

/** GET /content/recommend */
export type RecommendScene = 'latest' | 'hot';

/** GET /content/recommend */
export interface RecommendQuery {
  lastScore?: number;
  offset?: number;
  contentType?: 1 | 2;
  scene?: RecommendScene;
  pageSize: number;
}

/** GET /follow/feed */
export interface FollowFeedQuery {
  lastId?: number | string;
  offset?: number;
  contentType?: 1 | 2;
  pageSize: number;
}

/**
 * 首页 / 关注等列表的统一状态（cursor 存 lastScore 或 lastId 等游标）。
 */
export interface FeedState<T> {
  list: T[];
  loading: boolean;
  refreshing: boolean;
  loadingMore: boolean;
  finished: boolean;
  errorType?: ApiErrorType;
  errorMessage?: string;
  filter: ContentTypeFilter;
  cursor?: number | string;
  offset: number;
}
