import type { ContentTypeFilter } from '../constants/content';
import type { ApiErrorType } from './api';
import type { ContentCardModel } from './content';
import type { ContentVO } from './content';

/** 与后端 RagAnswer 对齐 */
export interface RagAnswerVO {
  enabled?: boolean | null;
  content?: string | null;
  reason?: string | null;
}

/** 与后端 RagSearchRequest 对齐（当前页仅 query / contentType / enableAi） */
export interface RagSearchRequest {
  query: string;
  contentType?: number;
  enableAi?: boolean;
}

/** 与后端 RagSearchResponse 对齐 */
export interface RagSearchResponse {
  aiAnswer?: RagAnswerVO | null;
  list?: ContentVO[] | null;
  total?: number | null;
  hasMore?: boolean;
}

export interface AiSummaryModel {
  keyword: string;
  /** 与搜索筛选一致，用于展示或二次请求 */
  filter: ContentTypeFilter;
  summary: string;
  sourceCount: number;
  loading: boolean;
  errorType: ApiErrorType | '';
  errorMessage: string;
}

export interface SearchResultPageState {
  keyword: string;
  filter: ContentTypeFilter;
  aiSummary: AiSummaryModel;
  aiLoading: boolean;
  aiErrorType: ApiErrorType | '';
  list: ContentCardModel[];
  loading: boolean;
  refreshing: boolean;
  loadingMore: boolean;
  finished: boolean;
  current: number;
  pageSize: number;
}

export interface SearchAiDetailState {
  keyword: string;
  filter: ContentTypeFilter;
  summary: string;
  sourceList: ContentCardModel[];
  loading: boolean;
  errorType: ApiErrorType | '';
  errorMessage: string;
}
