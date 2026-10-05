import {
  CONTENT_TYPE_ALL,
  CONTENT_TYPE_LIFE,
  CONTENT_TYPE_PROFESSIONAL,
  type ContentType,
  type ContentTypeFilter,
} from '../constants/content';
import { DEFAULT_PAGE_SIZE, USE_MOCK } from '../constants/request';
import { mockDelay } from '../mock/delay';
import { seedInteractionFromContentVO } from '../mock/detail-interaction';
import { findMockContentById, getRecommendMockPool } from '../mock/recommend-pool';
import { sliceMockScrollResult } from '../mock/scroll-slice';
import type { RecommendPageVO } from '../types/api';
import type { ContentDetailModel } from '../types/detail';
import type { ContentPublishPayload } from '../types/publish';
import type { ContentCardModel, ContentReportPayload, ContentVO } from '../types/content';
import type {
  RecommendQuery,
  RecommendQueryOptions,
  RecommendScene,
  RecommendationState,
} from '../types/feed';
import { bumpAppRefresh } from '../utils/app-refresh-bus';
import { request, type RequestResult } from '../utils/request';
import { normalizeImages, withDefaultAvatar } from '../utils/image';
import { getRecommendVisitor, type RecommendVisitor } from '../utils/recommend-visitor';

export interface RecommendFeedPage {
  list: ContentVO[];
  /** 后端明确 true/false；未返回时为 undefined，由页面结合条数判断 */
  hasMore?: boolean;
  /** 下一页请求的 lastScore */
  nextLastScore?: number;
  nextOffset: number;
  feedSessionId?: string;
  nextCursor?: string;
  recommendationState?: RecommendationState;
  canRevisit?: boolean;
}

function toContentType(n?: number | string): ContentType | undefined {
  const parsed =
    typeof n === 'string'
      ? Number(n.trim())
      : typeof n === 'number'
        ? n
        : NaN;
  if (parsed === CONTENT_TYPE_LIFE || parsed === CONTENT_TYPE_PROFESSIONAL) {
    return parsed;
  }
  return undefined;
}

export function mapContentVOToDetail(vo: ContentVO): ContentDetailModel | null {
  const card = mapContentVOToCard(vo);
  if (!card) {
    return null;
  }
  return {
    contentId: card.contentId,
    contentType: card.contentType,
    title: card.title,
    content: card.content,
    images: card.images ?? [],
    authorId: card.authorId,
    authorName: card.authorName,
    authorAvatar: card.authorAvatar,
    quantaDepartment: vo.quantaDepartment,
    quantaBatch: vo.quantaBatch,
    likeCount: vo.liked ?? card.likeCount,
    commentCount: vo.commentCount ?? card.commentCount,
    collectCount: vo.collectCount ?? card.collectCount,
    liked: vo.isLiked === true,
    collected: vo.isCollected === true,
    createTime: card.createTime,
  };
}

export function mapContentVOToCard(vo: ContentVO): ContentCardModel | null {
  const rawId = vo.contentId as unknown;
  const contentId =
    rawId === undefined || rawId === null ? NaN : Number(rawId);
  if (!Number.isFinite(contentId)) {
    console.warn('[content] skip item: invalid contentId', vo);
    return null;
  }

  const contentType = toContentType(vo.contentType);
  if (!contentType) {
    console.warn('[content] skip item: invalid contentType', vo);
    return null;
  }
  const rawAuthor = vo.publishUserId as unknown;
  const parsedAuthor =
    rawAuthor === undefined || rawAuthor === null || rawAuthor === ''
      ? NaN
      : Number(rawAuthor);
  const authorId =
    Number.isFinite(parsedAuthor) && parsedAuthor > 0 ? Math.floor(parsedAuthor) : 0;
  const authorName = (vo.nickName && vo.nickName.trim()) || '匿名用户';
  return {
    contentId,
    contentType,
    title: vo.title,
    content: vo.content,
    images: normalizeImages(vo.images),
    authorId,
    authorName,
    authorAvatar: withDefaultAvatar(vo.avatarUrl),
    likeCount: vo.liked ?? 0,
    collectCount: vo.collectCount ?? 0,
    commentCount: vo.commentCount ?? 0,
    answerCount: vo.answerCount,
    createTime:
      vo.createTime === undefined || vo.createTime === null
        ? undefined
        : String(vo.createTime),
    quantaDepartment: vo.quantaDepartment?.trim() || undefined,
    quantaBatch: vo.quantaBatch?.trim() || undefined,
    liked: vo.isLiked === true,
    collected: vo.isCollected === true,
    auditStatus:
      vo.auditStatus === undefined || vo.auditStatus === null
        ? undefined
        : Number(vo.auditStatus),
  };
}

export function buildRecommendQuery(
  filter: ContentTypeFilter,
  cursor: number | string | undefined,
  offset: number,
  pageSize: number = DEFAULT_PAGE_SIZE,
  scene: RecommendScene = 'recommend',
  options: RecommendQueryOptions = {},
): RecommendQuery {
  const q: RecommendQuery = {
    pageSize,
    offset: offset < 0 ? 0 : offset,
    scene,
  };
  if (filter !== CONTENT_TYPE_ALL) {
    q.contentType = filter;
  }
  if (typeof cursor === 'number' && !Number.isNaN(cursor)) {
    q.lastScore = cursor;
  } else if (typeof cursor === 'string' && cursor.trim()) {
    q.pageCursor = cursor;
  }
  if (options.feedSessionId?.trim()) {
    q.feedSessionId = options.feedSessionId.trim();
  }
  if (options.pageCursor?.trim()) {
    q.pageCursor = options.pageCursor.trim();
  }
  if (options.revisitOfSessionId?.trim()) {
    q.revisitOfSessionId = options.revisitOfSessionId.trim();
  }
  return q;
}

function buildFallbackMockContentVO(contentId: number): ContentVO {
  const isPro = contentId >= 2000 && contentId < 3000;
  const base: ContentVO = {
    contentId,
    contentType: isPro ? CONTENT_TYPE_PROFESSIONAL : CONTENT_TYPE_LIFE,
    title: isPro ? `Mock 专业问题 #${contentId}` : `Mock 生活帖 #${contentId}`,
    content:
      '此条为本地 Mock 占位正文。若需连真实后端：在 miniprogram/constants/request.ts 将 USE_MOCK 设为 false，并把 BASE_URL 改为本机局域网 IP（真机不可使用 127.0.0.1）。',
    publishUserId: 1,
    nickName: 'Mock用户',
    avatarUrl: '',
    images: [],
    liked: 0,
    isLiked: false,
    isCollected: false,
    commentCount: 0,
    collectCount: 0,
    createTime: '2026-05-13 12:00:00',
  };
  if (isPro) {
    base.answerCount = 0;
  }
  return base;
}

function compactQuery(q: RecommendQuery): Record<string, string | number> {
  const out: Record<string, string | number> = {
    pageSize: q.pageSize,
    offset: q.offset ?? 0,
  };
  if (q.contentType !== undefined) {
    out.contentType = q.contentType;
  }
  if (q.lastScore !== undefined && !Number.isNaN(q.lastScore)) {
    out.lastScore = q.lastScore;
  }
  if (q.pageCursor?.trim()) {
    out.pageCursor = q.pageCursor.trim();
  }
  if (q.scene !== undefined) {
    out.scene = q.scene;
  }
  if (q.feedSessionId?.trim()) {
    out.feedSessionId = q.feedSessionId.trim();
  }
  if (q.revisitOfSessionId?.trim()) {
    out.revisitOfSessionId = q.revisitOfSessionId.trim();
  }
  return out;
}

function recommendationFailure<T>(res: RequestResult<T>): RequestResult<T> {
  if (res.ok || res.statusCode !== 409) {
    return res;
  }
  return {
    ...res,
    errorType: 'recommendExpired',
    message: '推荐会话已过期，请刷新',
    businessCode: 409,
  };
}

function parseRecommendationState(value: unknown): RecommendationState | undefined {
  return value === 'READY' || value === 'SEARCHING' || value === 'EXHAUSTED'
    ? value
    : undefined;
}

function toRecommendPage(
  scroll: RecommendPageVO<ContentVO>,
): RecommendFeedPage {
  const nextLast =
    scroll.minScore === null || scroll.minScore === undefined
      ? undefined
      : Number(scroll.minScore);
  const nextOffset =
    scroll.offset === null || scroll.offset === undefined ? 0 : Number(scroll.offset);
  const nextCursor =
    scroll.nextCursor === null || scroll.nextCursor === undefined
      ? undefined
      : String(scroll.nextCursor);
  const feedSessionId =
    scroll.feedSessionId === null || scroll.feedSessionId === undefined
      ? undefined
      : String(scroll.feedSessionId);
  return {
    list: Array.isArray(scroll.list) ? scroll.list : [],
    hasMore: scroll.hasMore === true ? true : scroll.hasMore === false ? false : undefined,
    nextLastScore: Number.isNaN(nextLast as number) ? undefined : nextLast,
    nextOffset: Number.isNaN(nextOffset) ? 0 : nextOffset,
    ...(feedSessionId ? { feedSessionId } : {}),
    ...(nextCursor ? { nextCursor } : {}),
    ...(parseRecommendationState(scroll.recommendationState)
      ? { recommendationState: parseRecommendationState(scroll.recommendationState) }
      : {}),
    ...(typeof scroll.canRevisit === 'boolean' ? { canRevisit: scroll.canRevisit } : {}),
  };
}

export async function getRecommendFeed(
  query: RecommendQuery,
  visitorOverride?: RecommendVisitor,
): Promise<RequestResult<RecommendFeedPage>> {
  if (USE_MOCK) {
    await mockDelay();
    const pool = getRecommendMockPool(query.contentType, query.scene);
    const scroll = sliceMockScrollResult(pool, query.offset ?? 0, query.pageSize);
    if (!scroll.list || !Array.isArray(scroll.list)) {
      return { ok: false, errorType: 'invalidData', message: '推荐数据格式异常' };
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
        nextLastScore: Number.isNaN(nextLast as number) ? undefined : nextLast,
        nextOffset: Number.isNaN(nextOffset) ? 0 : nextOffset,
        ...(query.feedSessionId ? { feedSessionId: query.feedSessionId } : {}),
        ...(query.feedSessionId && scroll.hasMore === true
          ? { nextCursor: String(nextOffset) }
          : {}),
        ...(scroll.hasMore === false
          ? { recommendationState: 'EXHAUSTED' as const, canRevisit: true }
          : { recommendationState: 'READY' as const }),
      },
    };
  }

  const discoveryRequest = query.scene !== 'hot' && Boolean(query.feedSessionId);
  let visitor: RecommendVisitor | undefined;
  if (discoveryRequest) {
    try {
      visitor = visitorOverride ?? (await getRecommendVisitor());
    } catch {
      return { ok: false, errorType: 'invalidData', message: '无法生成游客标识，请重试' };
    }
  }
  const res = await request<RecommendPageVO<ContentVO>>({
    method: 'GET',
    url: '/content/recommend',
    data: compactQuery(query) as Record<string, unknown>,
    ...(visitor ? { header: { 'X-Guest-Id': visitor.guestId } } : {}),
  });
  const normalized = recommendationFailure(res);
  if (!normalized.ok) {
    return normalized;
  }
  const scroll = normalized.data;
  if (!scroll || !Array.isArray(scroll.list)) {
    return { ok: false, errorType: 'invalidData', message: '推荐数据格式异常' };
  }
  return { ok: true, data: toRecommendPage(scroll) };
}

/** 将进入可视区域的推荐内容回传给服务端；同一批次由调用方负责重试。 */
export async function reportRecommendExposures(payload: {
  feedSessionId: string;
  contentIds: number[];
}, visitorOverride?: RecommendVisitor): Promise<RequestResult<void>> {
  const contentIds = Array.from(
    new Set(
      payload.contentIds.filter(
        (contentId) => Number.isInteger(contentId) && contentId > 0,
      ),
    ),
  ).slice(0, 50);
  if (!payload.feedSessionId?.trim() || contentIds.length === 0) {
    return { ok: false, errorType: 'invalidData', message: '曝光参数无效' };
  }
  if (USE_MOCK) {
    await mockDelay();
    return { ok: true, data: undefined };
  }
  let visitor: RecommendVisitor;
  try {
    visitor = visitorOverride ?? (await getRecommendVisitor());
  } catch {
    return { ok: false, errorType: 'invalidData', message: '无法生成游客标识，请重试' };
  }
  const res = await request<void>({
    method: 'POST',
    url: '/content/recommend/exposures',
    data: { feedSessionId: payload.feedSessionId.trim(), contentIds },
    header: { 'X-Guest-Id': visitor.guestId },
  });
  return recommendationFailure(res);
}

/** 内容详情：需登录；非法 id 不发起请求 */
export async function getContentDetail(contentId: number): Promise<RequestResult<ContentVO>> {
  if (!Number.isFinite(contentId) || contentId <= 0) {
    return Promise.resolve({
      ok: false,
      errorType: 'invalidData',
      message: '内容不存在或参数无效',
    });
  }
  if (USE_MOCK) {
    await mockDelay();
    const found = findMockContentById(contentId);
    const vo = found ? { ...found } : buildFallbackMockContentVO(contentId);
    seedInteractionFromContentVO(vo);
    return { ok: true, data: vo };
  }
  return request<ContentVO>({
    method: 'GET',
    url: `/content/detail/${contentId}`,
  });
}

/** 删除内容 */
export async function deleteContent(contentId: number): Promise<RequestResult<void>> {
  if (!Number.isFinite(contentId) || contentId <= 0) {
    return Promise.resolve({
      ok: false,
      errorType: 'invalidData',
      message: '内容不存在或参数无效',
    });
  }
  if (USE_MOCK) {
    await mockDelay();
    bumpAppRefresh('myContent');
    return { ok: true, data: undefined as void };
  }
  const res = await request<void>({
    method: 'DELETE',
    url: `/content/delete/${contentId}`,
  });
  if (res.ok) {
    bumpAppRefresh('myContent');
  }
  return res;
}

/** 举报内容 */
export async function reportContent(payload: ContentReportPayload): Promise<RequestResult<void>> {
  if (!Number.isFinite(payload.contentId) || payload.contentId <= 0) {
    return Promise.resolve({
      ok: false,
      errorType: 'invalidData',
      message: '内容不存在或参数无效',
    });
  }
  const rt = Number(payload.reportType);
  if (!Number.isFinite(rt) || rt < 1 || rt > 5) {
    return Promise.resolve({
      ok: false,
      errorType: 'invalidData',
      message: '举报类型无效',
    });
  }
  if (USE_MOCK) {
    await mockDelay();
    return { ok: true, data: undefined as void };
  }
  return request<void>({
    method: 'POST',
    url: '/content/report',
    data: {
      contentId: payload.contentId,
      reportType: rt,
    },
  });
}

/** 发布内容：需登录 */
export async function publishContent(
  payload: ContentPublishPayload,
  submissionToken?: string,
): Promise<RequestResult<ContentVO>> {
  const res = await request<ContentVO>({
    method: 'POST',
    url: '/content/publish',
    data: {
      contentType: payload.contentType,
      title: payload.title,
      content: payload.content,
      images: payload.images,
    },
    ...(submissionToken ? { header: { 'Idempotency-Key': submissionToken } } : {}),
  });
  if (res.ok) {
    bumpAppRefresh('myContent');
  }
  return res;
}
