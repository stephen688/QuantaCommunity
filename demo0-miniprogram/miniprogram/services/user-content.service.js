"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.getMyContentList = getMyContentList;
exports.getMyLikedList = getMyLikedList;
exports.getMyCollectList = getMyCollectList;
exports.getBrowseHistoryList = getBrowseHistoryList;
exports.clearBrowseHistory = clearBrowseHistory;
const request_1 = require("../constants/request");
const delay_1 = require("../mock/delay");
const recommend_pool_1 = require("../mock/recommend-pool");
const request_2 = require("../utils/request");
const content_service_1 = require("./content.service");
function inferHasMore(page, fetchedLen) {
    if (page.hasMore === true) {
        return true;
    }
    if (page.hasMore === false) {
        return false;
    }
    const ps = page.pageSize ?? request_1.DEFAULT_PAGE_SIZE;
    const pn = page.pageNum ?? 1;
    if (typeof page.total === 'number' && page.total >= 0) {
        if (page.total === 0) {
            return false;
        }
        const totalPages = page.totalPage !== undefined && page.totalPage !== null
            ? page.totalPage
            : page.total > 0
                ? Math.ceil(page.total / ps)
                : 0;
        if (totalPages > 0) {
            return pn < totalPages;
        }
    }
    return fetchedLen >= ps;
}
function mapPageToCards(page) {
    const raw = page.list ?? [];
    const list = [];
    for (const vo of raw) {
        const c = (0, content_service_1.mapContentVOToCard)(vo);
        if (c) {
            if (request_1.USE_MOCK && (c.auditStatus === undefined || Number.isNaN(c.auditStatus))) {
                const mod = c.contentId % 4;
                c.auditStatus = mod === 0 ? 0 : mod === 1 ? 1 : mod === 2 ? 2 : 1;
            }
            list.push(c);
        }
    }
    return { list, hasMore: inferHasMore(page, raw.length) };
}
function mockContentPage(current, size) {
    const pool = (0, recommend_pool_1.getRecommendMockPool)();
    const start = (current - 1) * size;
    const list = pool.slice(start, start + size);
    const total = pool.length;
    const totalPage = total > 0 ? Math.ceil(total / size) : 0;
    const hasMore = current < totalPage;
    return {
        list,
        total,
        pageNum: current,
        pageSize: size,
        totalPage,
        hasMore,
    };
}
async function fetchMyContentPage(current, size, auditStatus) {
    if (request_1.USE_MOCK) {
        await (0, delay_1.mockDelay)();
        return { ok: true, data: mockContentPage(current, size) };
    }
    const c = current < 1 ? 1 : current;
    const s = size < 1 ? request_1.DEFAULT_PAGE_SIZE : size;
    const data = { current: c, size: s };
    if (auditStatus) {
        data.auditStatus = auditStatus;
    }
    return (0, request_2.request)({
        method: 'GET',
        url: '/user/content/my/list',
        data,
    });
}
async function fetchPagedContent(path, current, size) {
    if (request_1.USE_MOCK) {
        await (0, delay_1.mockDelay)();
        return { ok: true, data: mockContentPage(current, size) };
    }
    const c = current < 1 ? 1 : current;
    const s = size < 1 ? request_1.DEFAULT_PAGE_SIZE : size;
    return (0, request_2.request)({
        method: 'GET',
        url: path,
        data: { current: c, size: s },
    });
}
async function getMyContentList(current, size = request_1.DEFAULT_PAGE_SIZE, auditStatus) {
    const res = await fetchMyContentPage(current, size, auditStatus);
    if (!res.ok) {
        return res;
    }
    const mapped = mapPageToCards(res.data);
    return { ok: true, data: mapped };
}
async function getMyLikedList(current, size = request_1.DEFAULT_PAGE_SIZE) {
    const res = await fetchPagedContent('/user/content/my/liked', current, size);
    if (!res.ok) {
        return res;
    }
    return { ok: true, data: mapPageToCards(res.data) };
}
async function getMyCollectList(current, size = request_1.DEFAULT_PAGE_SIZE) {
    const res = await fetchPagedContent('/user/content/my/collect', current, size);
    if (!res.ok) {
        return res;
    }
    return { ok: true, data: mapPageToCards(res.data) };
}
async function getBrowseHistoryList(current, size = request_1.DEFAULT_PAGE_SIZE) {
    const res = await fetchPagedContent('/user/content/my/browseHistory', current, size);
    if (!res.ok) {
        return res;
    }
    return { ok: true, data: mapPageToCards(res.data) };
}
async function clearBrowseHistory() {
    if (request_1.USE_MOCK) {
        await (0, delay_1.mockDelay)();
        return { ok: true, data: undefined };
    }
    return (0, request_2.request)({
        method: 'DELETE',
        url: '/user/browse/history/clear',
    });
}
