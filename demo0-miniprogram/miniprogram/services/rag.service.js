"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.buildRagSearchRequest = buildRagSearchRequest;
exports.mapRagResponseToAiSummary = mapRagResponseToAiSummary;
exports.mapRagResponseToCards = mapRagResponseToCards;
exports.ragSearchContent = ragSearchContent;
exports.aiSummaryLoading = aiSummaryLoading;
const content_1 = require("../constants/content");
const request_1 = require("../constants/request");
const delay_1 = require("../mock/delay");
const request_2 = require("../utils/request");
const content_service_1 = require("./content.service");
const search_service_1 = require("./search.service");
function buildRagSearchRequest(keyword, filter, enableAi = true) {
    const q = {
        query: keyword.trim(),
        enableAi,
    };
    if (filter === content_1.CONTENT_TYPE_LIFE) {
        q.contentType = content_1.CONTENT_TYPE_LIFE;
    }
    else if (filter === content_1.CONTENT_TYPE_PROFESSIONAL) {
        q.contentType = content_1.CONTENT_TYPE_PROFESSIONAL;
    }
    return q;
}
function mapRagResponseToAiSummary(response, keyword, filter) {
    const empty = {
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
    const total = response.total !== undefined && response.total !== null ? Number(response.total) : listLen;
    const sourceCount = Number.isFinite(total) ? total : listLen;
    if (!ai || ai.enabled !== true || !ai.content?.trim()) {
        return {
            ...empty,
            sourceCount,
            errorType: ai?.enabled === false ? 'empty' : '',
            errorMessage: ai?.enabled === false && ai.reason
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
function mapRagResponseToCards(response) {
    const raw = response?.list ?? [];
    const out = [];
    for (const vo of raw) {
        const card = (0, content_service_1.mapContentVOToCard)(vo);
        if (card) {
            out.push(card);
        }
    }
    return out;
}
function mockRagFilter(payload) {
    const ct = payload.contentType;
    if (ct === content_1.CONTENT_TYPE_LIFE) {
        return content_1.CONTENT_TYPE_LIFE;
    }
    if (ct === content_1.CONTENT_TYPE_PROFESSIONAL) {
        return content_1.CONTENT_TYPE_PROFESSIONAL;
    }
    return content_1.CONTENT_TYPE_ALL;
}
function buildMockRagSummaryText(keyword, matchCount, filter) {
    const scope = filter === content_1.CONTENT_TYPE_LIFE ? '生活' : filter === content_1.CONTENT_TYPE_PROFESSIONAL ? '专业' : '全部';
    if (matchCount === 0) {
        return `（Mock）在「${scope}」分区未匹配到与「${keyword}」强相关的帖子。\n\n建议：切换到「全部」、缩短或更换关键词，或在首页浏览推荐内容。\n\n连接真实后端并开启 AI 后，此处将返回模型生成的检索式总结。`;
    }
    return `（Mock）检索式总结 ·「${scope}」分区 · 关键词「${keyword}」\n\n共检索到约 ${matchCount} 条相关帖子。从标题与摘要信息看，讨论主要集中在社区经验分享与技术问答两类场景。\n\n要点汇总（演示文案）：\n1. 优先关注高互动帖子中的共识方案与风险提示。\n2. 对专业问题建议结合回答数与最新动态判断可信度。\n3. 更细结论请进入具体帖子阅读全文与评论。\n\n连接真实后端并开启 RAG 后，此处为基于检索内容的答案摘要。`;
}
async function ragSearchContent(payload) {
    if (!payload.query || !payload.query.trim()) {
        return Promise.resolve({ ok: false, errorType: 'invalidData', message: '搜索关键词不能为空' });
    }
    if (request_1.USE_MOCK) {
        await (0, delay_1.mockDelay)();
        const filter = mockRagFilter(payload);
        const matched = (0, search_service_1.getMockMatchedContentVOs)(payload.query, filter);
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
    return (0, request_2.request)({
        method: 'POST',
        url: '/rag/search',
        data: {
            query: payload.query.trim(),
            contentType: payload.contentType,
            enableAi: payload.enableAi ?? true,
        },
    });
}
function aiSummaryLoading(keyword, filter) {
    return {
        keyword,
        filter,
        summary: '',
        sourceCount: 0,
        loading: true,
        errorType: '',
        errorMessage: '',
    };
}
