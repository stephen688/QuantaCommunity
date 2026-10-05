/** 对齐后端 Result<T>（code === 200 表示成功） */
export interface ApiResult<T> {
  code: number;
  msg: string;
  data?: T;
}

export type ApiErrorType =
  | 'network'
  | 'unauthorized'
  | 'forbidden'
  | 'rateLimited'
  | 'recommendExpired'
  | 'submissionPending'
  | 'submissionConflict'
  | 'submissionExpired'
  | 'server'
  | 'empty'
  | 'invalidData'
  | 'unknown';

export type HttpMethod =
  | 'OPTIONS'
  | 'GET'
  | 'POST'
  | 'PUT'
  | 'DELETE'
  | 'HEAD'
  | 'TRACE'
  | 'CONNECT';

export interface RequestOptions {
  method?: HttpMethod;
  url: string;
  data?: Record<string, unknown> | string | ArrayBuffer;
  header?: Record<string, string>;
  showErrorToast?: boolean;
}

/** 与后端 ScrollResult JSON 对齐 */
export interface ScrollResultVO<T = unknown> {
  list?: T[] | null;
  minScore?: number | null;
  offset?: number | null;
  hasMore?: boolean | null;
}

/** 与推荐会话协议对齐；旧服务未返回会话字段时保持可选。 */
export interface RecommendPageVO<T = unknown> extends ScrollResultVO<T> {
  feedSessionId?: string | null;
  nextCursor?: string | null;
  recommendationState?: 'READY' | 'SEARCHING' | 'EXHAUSTED' | string | null;
  canRevisit?: boolean | null;
}

/** 与后端 PageVO<T> 对齐 */
export interface PageVO<T = unknown> {
  list?: T[] | null;
  total?: number | null;
  totalPage?: number | null;
  pageNum?: number | null;
  pageSize?: number | null;
  hasMore?: boolean | null;
}
