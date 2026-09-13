import {
  CONTENT_TYPE_ALL,
  CONTENT_TYPE_LIFE,
  CONTENT_TYPE_PROFESSIONAL,
  type ContentTypeFilter,
} from '../constants/content';
import { USE_MOCK } from '../constants/request';
import { mockDelay } from '../mock/delay';
import type { ApiErrorType } from '../types/api';
import type { ContentCardModel } from '../types/content';
import type { AiSummaryModel, RagSearchRequest, RagSearchResponse } from '../types/rag';
import { request, type RequestResult } from '../utils/request';
import { mapContentVOToCard } from './content.service';
import { getMockMatchedContentVOs } from './search.service';

export function buildRagSearchRequest(
  keyword: string,
  filter: ContentTypeFilter,
  enableAi: boolean = true,
): RagSearchRequest {
  const q: RagSearchRequest = {
    query: keyword.trim(),
    enableAi,
  };
  if (filter === CONTENT_TYPE_LIFE) {
    q.contentType = CONTENT_TYPE_LIFE;
  } else if (filter === CONTENT_TYPE_PROFESSIONAL) {
    q.contentType = CONTENT_TYPE_PROFESSIONAL;
  }
  return q;
}

export function mapRagResponseToAiSummary(
  response: RagSearchResponse | null | undefined,
  keyword: string,
  filter: ContentTypeFilter,
): AiSummaryModel {
  const empty: AiSummaryModel = {
    keyword,
    filter,
    summary: '',
    sourceCount: 0,
    loading: false,
    errorType: '',
    errorMessage: '',
  };
  if (!response) {
    return empty;
  }
  const ai = response.aiAnswer;
  const listLen = response.list?.length ?? 0;
  const total =
    response.total !== undefined && response.total !== null ? Number(response.total) : listLen;
  const sourceCount = Number.isFinite(total) ? total : listLen;
  if (!ai || ai.enabled !== true || !ai.content?.trim()) {
    return {
      ...empty,
      sourceCount,
      errorType: ai?.enabled === false ? 'empty' : '',
      errorMessage:
        ai?.enabled === false && ai.reason
          ? String(ai.reason)
          : ai?.enabled === false
            ? '暂无法生成总结'
            : '',
    };
  }
  return {
    keyword,
    filter,
    summary: ai.content.trim(),
    sourceCount,
    loading: false,
    errorType: '',
    errorMessage: '',
  };
}

export function mapRagResponseToCards(
  response: RagSearchResponse | null | undefined,
): ContentCardModel[] {
  const raw = response?.list ?? [];
  const out: ContentCardModel[] = [];
  for (const vo of raw) {
    const card = mapContentVOToCard(vo);
    if (card) {
      out.push(card);
    }
  }
  return out;
}

function mockRagFilter(payload: RagSearchRequest): ContentTypeFilter {
  const ct = payload.contentType;
  if (ct === CONTENT_TYPE_LIFE) {
    return CONTENT_TYPE_LIFE;
  }
  if (ct === CONTENT_TYPE_PROFESSIONAL) {
    return CONTENT_TYPE_PROFESSIONAL;
  }
  return CONTENT_TYPE_ALL;
}

function buildMockRagSummaryText(keyword: string, matchCount: number, filter: ContentTypeFilter): string {
  const scope =
    filter === CONTENT_TYPE_LIFE ? '生活' : filter === CONTENT_TYPE_PROFESSIONAL ? '专业' : '全部';
  if (matchCount === 0) {
    return `（Mock）在「${scope}」分区未匹配到与「${keyword}」强相关的帖子。\n\n建议：切换到「全部」、缩短或更换关键词，或在首页浏览推荐内容。\n\n连接真实后端并开启 AI 后，此处将返回模型生成的检索式总结。`;
  }
  return `（Mock）检索式总结 ·「${scope}」分区 · 关键词「${keyword}」\n\n共检索到约 ${matchCount} 条相关帖子。从标题与摘要信息看，讨论主要集中在社区经验分享与技术问答两类场景。\n\n要点汇总（演示文案）：\n1. 优先关注高互动帖子中的共识方案与风险提示。\n2. 对专业问题建议结合回答数与最新动态判断可信度。\n3. 更细结论请进入具体帖子阅读全文与评论。\n\n连接真实后端并开启 RAG 后，此处为基于检索内容的答案摘要。`;
}

export async function ragSearchContent(
  payload: RagSearchRequest,
): Promise<RequestResult<RagSearchResponse>> {
  if (!payload.query || !payload.query.trim()) {
    return Promise.resolve({ ok: false, errorType: 'invalidData', message: '搜索关键词不能为空' });
  }
  if (USE_MOCK) {
    await mockDelay();
    const filter = mockRagFilter(payload);
    const matched = getMockMatchedContentVOs(payload.query, filter);
    const list = matched.slice(0, 8);
    const enableAi = payload.enableAi !== false;
    const summaryText = buildMockRagSummaryText(payload.query.trim(), matched.length, filter);
    return {
      ok: true,
      data: {
        aiAnswer: enableAi
          ? { enabled: true, content: summaryText }
          : { enabled: false, reason: '已关闭 AI 总结（Mock）' },
        list,
        total: matched.length,
        hasMore: false,
      },
    };
  }
  return request<RagSearchResponse>({
    method: 'POST',
    url: '/rag/search',
    data: {
      query: payload.query.trim(),
      contentType: payload.contentType,
      enableAi: payload.enableAi ?? true,
    },
  });
}

export function aiSummaryLoading(keyword: string, filter: ContentTypeFilter): AiSummaryModel {
  return {
    keyword,
    filter,
    summary: '',
    sourceCount: 0,
    loading: true,
    errorType: '' as ApiErrorType | '',
    errorMessage: '',
  };
}
