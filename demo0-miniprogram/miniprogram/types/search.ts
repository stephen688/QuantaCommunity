import type { ContentTypeFilter } from '../constants/content';
import type { ApiErrorType } from './api';
import type { ContentCardModel } from './content';

export type SearchSortType = 'new' | 'hot';

/** 与后端 SearchDTO 查询参数对齐 */
export interface SearchQuery {
  keyword: string;
  contentType?: number;
  sortType?: SearchSortType;
  current: number;
  pageSize: number;
}

/** 搜索页聚合状态 */
/** 搜索页热门推荐（2.1 已确认） */
export interface SearchHotQuestion {
  contentId: number;
  title: string;
  /** 后端 HotQuestionVO 无此字段时需点击时再拉详情 */
  contentType?: number;
  heatLabel?: string;
}

/** 与后端 SearchTrendingVO JSON 对齐 */
export interface SearchTrendingVO {
  hotKeywords?: string[];
  hotQuestions?: SearchTrendingQuestionVO[];
  hotAlumni?: SearchTrendingAlumniVO[];
}

export interface SearchTrendingQuestionVO {
  contentId: number;
  title?: string;
  liked?: number;
}

export interface SearchTrendingAlumniVO {
  userId: number;
  nickName?: string;
  avatarUrl?: string;
}

export interface SearchHotAlumni {
  userId: number;
  nickName: string;
  avatarUrl: string;
  subtitle?: string;
  avatarInitial?: string;
}

export interface SearchHotRecommendData {
  keywords: string[];
  questions: SearchHotQuestion[];
  alumni: SearchHotAlumni[];
}

export interface SearchState {
  keyword: string;
  inputValue: string;
  filter: ContentTypeFilter;
  historyKeywords: string[];
  list: ContentCardModel[];
  loading: boolean;
  refreshing: boolean;
  loadingMore: boolean;
  finished: boolean;
  errorType: ApiErrorType | '';
  errorMessage: string;
  current: number;
  pageSize: number;
  searched: boolean;
}
