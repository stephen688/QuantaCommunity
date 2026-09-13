"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.getMockFollowedForUser = getMockFollowedForUser;
exports.getMockFollowerAdjust = getMockFollowerAdjust;
exports.buildFollowFeedQuery = buildFollowFeedQuery;
exports.getFollowFeed = getFollowFeed;
exports.setFollowUser = setFollowUser;
const content_1 = require("../constants/content");
const request_1 = require("../constants/request");
const follow_pool_1 = require("../mock/follow-pool");
const delay_1 = require("../mock/delay");
const scroll_slice_1 = require("../mock/scroll-slice");
const follow_feed_bus_1 = require("../utils/follow-feed-bus");
const request_2 = require("../utils/request");
/** Mock：关注状态覆盖（undefined 表示未覆盖，走默认规则） */
const mockFollowOverride = new Map();
/** Mock：相对初始粉丝数的会话内增量 */
const mockFollowerAdjust = new Map();
function defaultMockFollowed(targetUserId) {
    return targetUserId % 3 !== 0;
}
function getMockFollowedForUser(targetUserId) {
    if (mockFollowOverride.has(targetUserId)) {
        return mockFollowOverride.get(targetUserId);
    }
    return defaultMockFollowed(targetUserId);
}
function getMockFollowerAdjust(targetUserId) {
    return mockFollowerAdjust.get(targetUserId) ?? 0;
}
function buildFollowFeedQuery(filter, cursor, offset, pageSize = request_1.DEFAULT_PAGE_SIZE) {
    const q = {
        pageSize,
        offset: offset < 0 ? 0 : offset,
    };
    if (filter !== content_1.CONTENT_TYPE_ALL) {
        q.contentType = filter;
    }
    if (cursor !== undefined && cursor !== null && cursor !== '') {
        const n = typeof cursor === 'string' ? Number(cursor) : cursor;
        if (!Number.isNaN(n)) {
            q.lastId = n;
        }
    }
    return q;
}
function compactFollowQuery(q) {
    const out = {
        pageSize: q.pageSize,
        offset: q.offset ?? 0,
    };
    if (q.contentType !== undefined) {
        out.contentType = q.contentType;
    }
    if (q.lastId !== undefined && q.lastId !== '') {
        const raw = typeof q.lastId === 'string' ? Number(q.lastId) : q.lastId;
        if (!Number.isNaN(raw)) {
            out.lastId = raw;
        }
    }
    return out;
}
async function getFollowFeed(query) {
    if (request_1.USE_MOCK) {
        await (0, delay_1.mockDelay)();
        const pool = (0, follow_pool_1.getFollowMockPool)(query.contentType);
        const scroll = (0, scroll_slice_1.sliceMockScrollResult)(pool, query.offset ?? 0, query.pageSize);
        if (!scroll.list || !Array.isArray(scroll.list)) {
            return { ok: false, errorType: 'invalidData', message: '关注动态数据格式异常' };
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
                nextLastId: Number.isNaN(nextLast) ? undefined : nextLast,
                nextOffset: Number.isNaN(nextOffset) ? 0 : nextOffset,
            },
        };
    }
    const res = await (0, request_2.request)({
        method: 'GET',
        url: '/follow/feed',
        data: compactFollowQuery(query),
    });
    if (!res.ok) {
        return res;
    }
    const scroll = res.data;
    if (!scroll || !Array.isArray(scroll.list)) {
        return { ok: false, errorType: 'invalidData', message: '关注动态数据格式异常' };
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
            nextLastId: Number.isNaN(nextLast) ? undefined : nextLast,
            nextOffset: Number.isNaN(nextOffset) ? 0 : nextOffset,
        },
    };
}
/** 关注 / 取关用户 */
async function setFollowUser(userId, followed) {
    if (!Number.isFinite(userId) || userId <= 0) {
        return Promise.resolve({
            ok: false,
            errorType: 'invalidData',
            message: '用户不存在或参数无效',
        });
    }
    if (typeof followed !== 'boolean') {
        return Promise.resolve({
            ok: false,
            errorType: 'invalidData',
            message: '关注状态无效',
        });
    }
    if (request_1.USE_MOCK) {
        await (0, delay_1.mockDelay)();
        const cur = getMockFollowedForUser(userId);
        const next = followed;
        mockFollowOverride.set(userId, next);
        let delta = 0;
        if (next && !cur) {
            delta = 1;
        }
        else if (!next && cur) {
            delta = -1;
        }
        mockFollowerAdjust.set(userId, getMockFollowerAdjust(userId) + delta);
        const followerBase = 8 + (userId % 89);
        const fc = Math.max(0, followerBase + getMockFollowerAdjust(userId));
        (0, follow_feed_bus_1.markFollowFeedStale)();
        return {
            ok: true,
            data: { isFollowed: next, followCount: fc, followerCount: fc },
        };
    }
    const res = await (0, request_2.request)({
        method: 'POST',
        url: `/follow/${userId}`,
        data: { followed },
    });
    if (!res.ok) {
        return res;
    }
    const raw = res.data;
    const isFollowed = raw?.isFollowed === true;
    const fc = raw?.followCount === undefined || raw?.followCount === null ? undefined : Number(raw.followCount);
    const out = {
        isFollowed,
        followCount: Number.isFinite(fc) ? fc : undefined,
        followerCount: Number.isFinite(fc) ? fc : undefined,
    };
    (0, follow_feed_bus_1.markFollowFeedStale)();
    return { ok: true, data: out };
}
