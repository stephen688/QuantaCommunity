"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.getMockMatchedContentVOs = getMockMatchedContentVOs;
exports.buildSearchQuery = buildSearchQuery;
exports.mapSearchPageToCards = mapSearchPageToCards;
exports.searchContent = searchContent;
exports.getSearchTrending = getSearchTrending;
exports.getSearchHotRecommend = getSearchHotRecommend;
exports.getSearchHistoryKeywords = getSearchHistoryKeywords;
exports.clearSearchHistory = clearSearchHistory;
exports.deleteSearchHistoryOne = deleteSearchHistoryOne;
const content_1 = require("../constants/content");
const request_1 = require("../constants/request");
const delay_1 = require("../mock/delay");
const recommend_pool_1 = require("../mock/recommend-pool");
const request_2 = require("../utils/request");
const content_service_1 = require("./content.service");
/** Mock 模式下内存维护搜索关键词历史（与真实接口行为一致，不落盘） */
let mockSearchKeywordHistory = [];
const MOCK_HISTORY_MAX = 30;
function normalizeKeywordForMatch(s) {
    return s.trim().toLowerCase();
}
function mockPoolForFilter(filter) {
    if (filter === content_1.CONTENT_TYPE_LIFE) {
        return (0, recommend_pool_1.getRecommendMockPool)(content_1.CONTENT_TYPE_LIFE);
    }
    if (filter === content_1.CONTENT_TYPE_PROFESSIONAL) {
        return (0, recommend_pool_1.getRecommendMockPool)(content_1.CONTENT_TYPE_PROFESSIONAL);
    }
    return (0, recommend_pool_1.getRecommendMockPool)();
}
function filterMockSearchResults(pool, keyword) {
    const k = normalizeKeywordForMatch(keyword);
    if (!k) {
        return [];
    }
    return pool.filter((vo) => {
        const idStr = String(vo.contentId ?? '');
        const title = normalizeKeywordForMatch(vo.title || '');
        const content = normalizeKeywordForMatch(vo.content || '');
        const nick = normalizeKeywordForMatch(vo.nickName || '');
        return (idStr.includes(keyword.trim()) ||
            title.includes(k) ||
            content.includes(k) ||
            nick.includes(k));
    });
}
function pushMockHistoryKeyword(keyword) {
    const k = keyword.trim();
    if (!k) {
        return;
    }
    mockSearchKeywordHistory = [k, ...mockSearchKeywordHistory.filter((x) => x !== k)].slice(0, MOCK_HISTORY_MAX);
}
/**
 * Mock：与关键词搜索相同过滤逻辑，供 RAG Mock 返回「相关帖子」摘要列表。
 */
function getMockMatchedContentVOs(keyword, filter) {
    const pool = mockPoolForFilter(filter);
    return filterMockSearchResults(pool, keyword);
}
function buildSearchQuery(keyword, filter, current, pageSize = request_1.DEFAULT_PAGE_SIZE, sortType = 'new') {
    const q = {
        keyword: keyword.trim(),
        current: current < 1 ? 1 : current,
        pageSize: pageSize < 1 ? request_1.DEFAULT_PAGE_SIZE : pageSize,
        sortType,
    };
    if (filter === content_1.CONTENT_TYPE_LIFE) {
        q.contentType = content_1.CONTENT_TYPE_LIFE;
    }
    else if (filter === content_1.CONTENT_TYPE_PROFESSIONAL) {
        q.contentType = content_1.CONTENT_TYPE_PROFESSIONAL;
    }
    return q;
}
function mapSearchPageToCards(page) {
    const raw = page.list ?? [];
    const list = [];
    for (const vo of raw) {
        const card = (0, content_service_1.mapContentVOToCard)(vo);
        if (card) {
            list.push(card);
        }
    }
    const total = page.total === undefined || page.total === null ? list.length : Number(page.total);
    const hasMore = page.hasMore === true;
    return { list, hasMore, total: Number.isFinite(total) ? total : list.length };
}
async function searchContent(query) {
    if (!query.keyword || !query.keyword.trim()) {
        return { ok: false, errorType: 'invalidData', message: '请输入搜索关键词' };
    }
    if (request_1.USE_MOCK) {
        await (0, delay_1.mockDelay)();
        const filter = query.contentType === content_1.CONTENT_TYPE_LIFE
            ? content_1.CONTENT_TYPE_LIFE
            : query.contentType === content_1.CONTENT_TYPE_PROFESSIONAL
                ? content_1.CONTENT_TYPE_PROFESSIONAL
                : content_1.CONTENT_TYPE_ALL;
        let matched = getMockMatchedContentVOs(query.keyword, filter);
        if (query.sortType === 'hot') {
            matched = [...matched].sort((a, b) => (b.liked ?? 0) - (a.liked ?? 0));
        }
        const pageSize = query.pageSize < 1 ? request_1.DEFAULT_PAGE_SIZE : query.pageSize;
        const current = query.current < 1 ? 1 : query.current;
        const start = (current - 1) * pageSize;
        const slice = matched.slice(start, start + pageSize);
        const hasMore = start + slice.length < matched.length;
        pushMockHistoryKeyword(query.keyword.trim());
        return {
            ok: true,
            data: {
                list: slice,
                total: matched.length,
                hasMore,
                pageNum: current,
                pageSize,
            },
        };
    }
    const data = {
        keyword: query.keyword.trim(),
        current: query.current,
        pageSize: query.pageSize,
        sortType: query.sortType ?? 'new',
    };
    if (query.contentType !== undefined) {
        data.contentType = query.contentType;
    }
    return (0, request_2.request)({
        method: 'GET',
        url: '/search/content',
        data: data,
    });
}
const MOCK_HOT_KEYWORDS = ['露营', 'Spring Boot', '小程序分包', '校友聚会', '考研复习'];
function buildMockHotRecommend() {
    const pool = mockPoolForFilter(content_1.CONTENT_TYPE_ALL);
    const hotPool = [...pool].sort((a, b) => (b.liked ?? 0) - (a.liked ?? 0));
    const questions = hotPool.slice(0, 3).map((vo) => {
        const ct = Number(vo.contentType);
        return {
            contentId: Number(vo.contentId),
            title: String(vo.title || '').trim() || '热门问题',
            contentType: ct === content_1.CONTENT_TYPE_LIFE || ct === content_1.CONTENT_TYPE_PROFESSIONAL ? ct : undefined,
            heatLabel: `${vo.liked ?? 0} 赞`,
        };
    });
    const seenUsers = new Set();
    const alumni = [];
    const deptLabels = ['计算机学院 · 18 届', '经管学院 · 19 届', '外语学院 · 17 届', '土木学院 · 20 届'];
    for (const vo of hotPool) {
        const uid = Number(vo.publishUserId);
        if (!Number.isFinite(uid) || uid <= 0 || seenUsers.has(uid)) {
            continue;
        }
        seenUsers.add(uid);
        const nickName = String(vo.nickName || `校友${uid}`);
        alumni.push({
            userId: uid,
            nickName,
            avatarUrl: String(vo.avatarUrl || ''),
            subtitle: deptLabels[alumni.length % deptLabels.length],
            avatarInitial: nickName.charAt(0) || '友',
        });
        if (alumni.length >= 6) {
            break;
        }
    }
    return {
        keywords: [...MOCK_HOT_KEYWORDS],
        questions,
        alumni,
    };
}
function isEmptyHotRecommend(data) {
    if (!data) {
        return true;
    }
    return (!data.keywords?.length && !data.questions?.length && !data.alumni?.length);
}
function mapTrendingToRecommend(vo) {
    const keywords = vo.hotKeywords ?? [];
    const questions = (vo.hotQuestions ?? [])
        .map((q) => {
        const contentId = Number(q.contentId);
        if (!Number.isFinite(contentId) || contentId <= 0) {
            return null;
        }
        const title = String(q.title ?? '').trim() || '热门问题';
        const liked = q.liked;
        return {
            contentId,
            title,
            heatLabel: liked === undefined || liked === null ? undefined : `${liked} 赞`,
        };
    })
        .filter((q) => q !== null);
    const alumni = (vo.hotAlumni ?? [])
        .map((a) => {
        const userId = Number(a.userId);
        if (!Number.isFinite(userId) || userId <= 0) {
            return null;
        }
        const nickName = String(a.nickName ?? '').trim() || '校友';
        return {
            userId,
            nickName,
            avatarUrl: String(a.avatarUrl ?? ''),
            avatarInitial: nickName.charAt(0) || '友',
        };
    })
        .filter((a) => a !== null);
    return { keywords, questions, alumni };
}
/** 搜索页热门发现（对接 GET /search/trending） */
async function getSearchTrending() {
    if (request_1.USE_MOCK) {
        await (0, delay_1.mockDelay)();
        return { ok: true, data: buildMockHotRecommend() };
    }
    const res = await (0, request_2.request)({
        method: 'GET',
        url: '/search/trending',
    });
    if (!res.ok) {
        return res;
    }
    const mapped = mapTrendingToRecommend(res.data ?? {});
    if (isEmptyHotRecommend(mapped)) {
        return { ok: true, data: { keywords: [], questions: [], alumni: [] } };
    }
    return { ok: true, data: mapped };
}
/** @deprecated 使用 getSearchTrending */
async function getSearchHotRecommend() {
    return getSearchTrending();
}
async function getSearchHistoryKeywords() {
    if (request_1.USE_MOCK) {
        await (0, delay_1.mockDelay)();
        return { ok: true, data: [...mockSearchKeywordHistory] };
    }
    return (0, request_2.request)({
        method: 'GET',
        url: '/search/history/keywords',
    });
}
async function clearSearchHistory() {
    if (request_1.USE_MOCK) {
        await (0, delay_1.mockDelay)();
        mockSearchKeywordHistory = [];
        return { ok: true, data: undefined };
    }
    return (0, request_2.request)({
        method: 'DELETE',
        url: '/search/history/clear',
    });
}
function deleteSearchHistoryOne(id) {
    if (!Number.isFinite(id) || id <= 0) {
        return Promise.resolve({
            ok: false,
            errorType: 'invalidData',
            message: '历史记录无效',
        });
    }
    if (request_1.USE_MOCK) {
        return (async () => {
            await (0, delay_1.mockDelay)();
            return { ok: true, data: undefined };
        })();
    }
    return (0, request_2.request)({
        method: 'DELETE',
        url: `/search/history/deleteOne/${id}`,
    });
}
