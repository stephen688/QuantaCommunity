"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.mapContentVOToDetail = mapContentVOToDetail;
exports.mapContentVOToCard = mapContentVOToCard;
exports.buildRecommendQuery = buildRecommendQuery;
exports.getRecommendFeed = getRecommendFeed;
exports.getContentDetail = getContentDetail;
exports.deleteContent = deleteContent;
exports.reportContent = reportContent;
exports.publishContent = publishContent;
const content_1 = require("../constants/content");
const request_1 = require("../constants/request");
const delay_1 = require("../mock/delay");
const detail_interaction_1 = require("../mock/detail-interaction");
const recommend_pool_1 = require("../mock/recommend-pool");
const scroll_slice_1 = require("../mock/scroll-slice");
const app_refresh_bus_1 = require("../utils/app-refresh-bus");
const request_2 = require("../utils/request");
const image_1 = require("../utils/image");
function toContentType(n) {
    const parsed = typeof n === 'string'
        ? Number(n.trim())
        : typeof n === 'number'
            ? n
            : NaN;
    if (parsed === content_1.CONTENT_TYPE_LIFE || parsed === content_1.CONTENT_TYPE_PROFESSIONAL) {
        return parsed;
    }
    return undefined;
}
function mapContentVOToDetail(vo) {
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
function mapContentVOToCard(vo) {
    const rawId = vo.contentId;
    const contentId = rawId === undefined || rawId === null ? NaN : Number(rawId);
    if (!Number.isFinite(contentId)) {
        console.warn('[content] skip item: invalid contentId', vo);
        return null;
    }
    const contentType = toContentType(vo.contentType);
    if (!contentType) {
        console.warn('[content] skip item: invalid contentType', vo);
        return null;
    }
    const rawAuthor = vo.publishUserId;
    const parsedAuthor = rawAuthor === undefined || rawAuthor === null || rawAuthor === ''
        ? NaN
        : Number(rawAuthor);
    const authorId = Number.isFinite(parsedAuthor) && parsedAuthor > 0 ? Math.floor(parsedAuthor) : 0;
    const authorName = (vo.nickName && vo.nickName.trim()) || '匿名用户';
    return {
        contentId,
        contentType,
        title: vo.title,
        content: vo.content,
        images: (0, image_1.normalizeImages)(vo.images),
        authorId,
        authorName,
        authorAvatar: (0, image_1.withDefaultAvatar)(vo.avatarUrl),
        likeCount: vo.liked ?? 0,
        collectCount: vo.collectCount ?? 0,
        commentCount: vo.commentCount ?? 0,
        answerCount: vo.answerCount,
        createTime: vo.createTime === undefined || vo.createTime === null
            ? undefined
            : String(vo.createTime),
        quantaDepartment: vo.quantaDepartment?.trim() || undefined,
        quantaBatch: vo.quantaBatch?.trim() || undefined,
        liked: vo.isLiked === true,
        collected: vo.isCollected === true,
        auditStatus: vo.auditStatus === undefined || vo.auditStatus === null
            ? undefined
            : Number(vo.auditStatus),
    };
}
function buildRecommendQuery(filter, cursor, offset, pageSize = request_1.DEFAULT_PAGE_SIZE, scene = 'latest') {
    const q = {
        pageSize,
        offset: offset < 0 ? 0 : offset,
        scene,
    };
    if (filter !== content_1.CONTENT_TYPE_ALL) {
        q.contentType = filter;
    }
    if (typeof cursor === 'number' && !Number.isNaN(cursor)) {
        q.lastScore = cursor;
    }
    return q;
}
function buildFallbackMockContentVO(contentId) {
    const isPro = contentId >= 2000 && contentId < 3000;
    const base = {
        contentId,
        contentType: isPro ? content_1.CONTENT_TYPE_PROFESSIONAL : content_1.CONTENT_TYPE_LIFE,
        title: isPro ? `Mock 专业问题 #${contentId}` : `Mock 生活帖 #${contentId}`,
        content: '此条为本地 Mock 占位正文。若需连真实后端：在 miniprogram/constants/request.ts 将 USE_MOCK 设为 false，并把 BASE_URL 改为本机局域网 IP（真机不可使用 127.0.0.1）。',
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
function compactQuery(q) {
    const out = {
        pageSize: q.pageSize,
        offset: q.offset ?? 0,
    };
    if (q.contentType !== undefined) {
        out.contentType = q.contentType;
    }
    if (q.lastScore !== undefined && !Number.isNaN(q.lastScore)) {
        out.lastScore = q.lastScore;
    }
    if (q.scene !== undefined) {
        out.scene = q.scene;
    }
    return out;
}
async function getRecommendFeed(query) {
    if (request_1.USE_MOCK) {
        await (0, delay_1.mockDelay)();
        const pool = (0, recommend_pool_1.getRecommendMockPool)(query.contentType, query.scene);
        const scroll = (0, scroll_slice_1.sliceMockScrollResult)(pool, query.offset ?? 0, query.pageSize);
        if (!scroll.list || !Array.isArray(scroll.list)) {
            return { ok: false, errorType: 'invalidData', message: '推荐数据格式异常' };
        }
        const hasMore = scroll.hasMore === true ? true : scroll.hasMore === false ? false : undefined;
        const nextLast = scroll.minScore === null || scroll.minScore === undefined
            ? undefined
            : Number(scroll.minScore);
        const nextOffset = scroll.offset === null || scroll.offset === undefined ? 0 : Number(scroll.offset);
        return {
            ok: true,
            data: {
                list: scroll.list,
                hasMore,
                nextLastScore: Number.isNaN(nextLast) ? undefined : nextLast,
                nextOffset: Number.isNaN(nextOffset) ? 0 : nextOffset,
            },
        };
    }
    const res = await (0, request_2.request)({
        method: 'GET',
        url: '/content/recommend',
        data: compactQuery(query),
    });
    if (!res.ok) {
        return res;
    }
    const scroll = res.data;
    if (!scroll || !Array.isArray(scroll.list)) {
        return { ok: false, errorType: 'invalidData', message: '推荐数据格式异常' };
    }
    const hasMore = scroll.hasMore === true ? true : scroll.hasMore === false ? false : undefined;
    const nextLast = scroll.minScore === null || scroll.minScore === undefined
        ? undefined
        : Number(scroll.minScore);
    const nextOffset = scroll.offset === null || scroll.offset === undefined ? 0 : Number(scroll.offset);
    return {
        ok: true,
        data: {
            list: scroll.list,
            hasMore,
            nextLastScore: Number.isNaN(nextLast) ? undefined : nextLast,
            nextOffset: Number.isNaN(nextOffset) ? 0 : nextOffset,
        },
    };
}
/** 内容详情：需登录；非法 id 不发起请求 */
async function getContentDetail(contentId) {
    if (!Number.isFinite(contentId) || contentId <= 0) {
        return Promise.resolve({
            ok: false,
            errorType: 'invalidData',
            message: '内容不存在或参数无效',
        });
    }
    if (request_1.USE_MOCK) {
        await (0, delay_1.mockDelay)();
        const found = (0, recommend_pool_1.findMockContentById)(contentId);
        const vo = found ? { ...found } : buildFallbackMockContentVO(contentId);
        (0, detail_interaction_1.seedInteractionFromContentVO)(vo);
        return { ok: true, data: vo };
    }
    return (0, request_2.request)({
        method: 'GET',
        url: `/content/detail/${contentId}`,
    });
}
/** 删除内容 */
async function deleteContent(contentId) {
    if (!Number.isFinite(contentId) || contentId <= 0) {
        return Promise.resolve({
            ok: false,
            errorType: 'invalidData',
            message: '内容不存在或参数无效',
        });
    }
    if (request_1.USE_MOCK) {
        await (0, delay_1.mockDelay)();
        (0, app_refresh_bus_1.bumpAppRefresh)('myContent');
        return { ok: true, data: undefined };
    }
    const res = await (0, request_2.request)({
        method: 'DELETE',
        url: `/content/delete/${contentId}`,
    });
    if (res.ok) {
        (0, app_refresh_bus_1.bumpAppRefresh)('myContent');
    }
    return res;
}
/** 举报内容 */
async function reportContent(payload) {
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
    if (request_1.USE_MOCK) {
        await (0, delay_1.mockDelay)();
        return { ok: true, data: undefined };
    }
    return (0, request_2.request)({
        method: 'POST',
        url: '/content/report',
        data: {
            contentId: payload.contentId,
            reportType: rt,
        },
    });
}
/** 发布内容：需登录 */
async function publishContent(payload) {
    const res = await (0, request_2.request)({
        method: 'POST',
        url: '/content/publish',
        data: {
            contentType: payload.contentType,
            title: payload.title,
            content: payload.content,
            images: payload.images,
        },
    });
    if (res.ok) {
        (0, app_refresh_bus_1.bumpAppRefresh)('myContent');
    }
    return res;
}
