"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.getUserInfo = getUserInfo;
exports.updateUserInfo = updateUserInfo;
exports.resolveProfileDisplayAuthStatus = resolveProfileDisplayAuthStatus;
exports.mapUserProfileVOToModel = mapUserProfileVOToModel;
exports.getUserProfile = getUserProfile;
exports.getUserPublicContents = getUserPublicContents;
const content_1 = require("../constants/content");
const request_1 = require("../constants/request");
const delay_1 = require("../mock/delay");
const follow_pool_1 = require("../mock/follow-pool");
const recommend_pool_1 = require("../mock/recommend-pool");
const user_notification_mock_1 = require("../mock/user-notification.mock");
const user_1 = require("../types/user");
const app_refresh_bus_1 = require("../utils/app-refresh-bus");
const request_2 = require("../utils/request");
const storage_1 = require("../utils/storage");
const content_service_1 = require("./content.service");
const follow_service_1 = require("./follow.service");
async function getUserInfo() {
    if (request_1.USE_MOCK) {
        await (0, delay_1.mockDelay)();
        return { ok: true, data: { ...user_notification_mock_1.MOCK_USER_INFO } };
    }
    const res = await (0, request_2.request)({
        method: 'GET',
        url: '/user/info',
    });
    if (!res.ok) {
        return res;
    }
    const data = res.data;
    if (data === undefined || data === null || typeof data !== 'object') {
        return { ok: false, errorType: 'invalidData', message: '用户信息数据异常' };
    }
    // /user/info 当前仅返回头像+昵称，缺少 userId；从登录态缓存补齐，供“本人评论删除”等权限判断使用。
    const normalized = { ...data };
    const rawUserId = normalized.userId === undefined || normalized.userId === null
        ? NaN
        : Number(normalized.userId);
    if (!Number.isFinite(rawUserId) || rawUserId <= 0) {
        const cachedUserId = (0, storage_1.getLoginUserId)();
        if (cachedUserId !== undefined) {
            normalized.userId = cachedUserId;
        }
    }
    (0, app_refresh_bus_1.setCachedUserInfo)(normalized);
    return { ok: true, data: normalized };
}
async function updateUserInfo(payload) {
    if (request_1.USE_MOCK) {
        await (0, delay_1.mockDelay)();
        return { ok: true, data: undefined };
    }
    const res = await (0, request_2.request)({
        method: 'PUT',
        url: '/user/info/update',
        data: { ...payload },
    });
    if (res.ok) {
        (0, app_refresh_bus_1.mergeCachedUserInfo)({
            nickName: payload.nickName,
            avatarUrl: payload.avatarUrl,
        });
        (0, app_refresh_bus_1.bumpAppRefresh)('userProfile');
    }
    return res;
}
/** 认证表 audit_status=1（已通过）时，主页展示态应为 2（已认证） */
function resolveProfileDisplayAuthStatus(profileAuthStatus, auditStatus) {
    if (auditStatus === 1) {
        return 2;
    }
    return profileAuthStatus ?? 0;
}
function mapUserProfileVOToModel(vo, auditStatus) {
    const uid = vo.userId === undefined || vo.userId === null ? NaN : Number(vo.userId);
    if (!Number.isFinite(uid) || uid <= 0) {
        return null;
    }
    const rawNick = [vo.nickName, vo.nickname].find((s) => s && String(s).trim());
    const nickname = rawNick ? String(rawNick).trim() : '匿名用户';
    const authStatus = resolveProfileDisplayAuthStatus(vo.authStatus, auditStatus);
    return {
        userId: uid,
        nickname,
        avatarUrl: vo.avatarUrl ?? '',
        authStatus,
        authStatusText: (0, user_1.userProfileAuthStatusText)(authStatus),
        quantaDepartment: vo.quantaDepartment ?? '',
        quantaBatch: vo.quantaBatch ?? '',
        contentCount: vo.contentCount ?? 0,
        followingCount: vo.followingCount ?? 0,
        followerCount: vo.followerCount ?? 0,
        isFollowed: vo.isFollowed === true,
        isSelf: vo.isSelf === true,
    };
}
function collectAllMockContents() {
    const seen = new Set();
    const out = [];
    const pushPool = (pool) => {
        for (const c of pool) {
            const id = Number(c.contentId);
            if (!Number.isFinite(id) || seen.has(id)) {
                continue;
            }
            seen.add(id);
            out.push(c);
        }
    };
    pushPool((0, recommend_pool_1.getRecommendMockPool)());
    pushPool((0, follow_pool_1.getFollowMockPool)());
    pushPool((0, follow_pool_1.getFollowMockPool)(content_1.CONTENT_TYPE_LIFE));
    pushPool((0, follow_pool_1.getFollowMockPool)(content_1.CONTENT_TYPE_PROFESSIONAL));
    return out;
}
function mockSyntheticPublicContents(userId) {
    const n = 2 + (userId % 2);
    const nickName = `用户${userId}`;
    const rows = [];
    for (let i = 0; i < n; i += 1) {
        const contentId = 6000000 + userId * 10 + i;
        const isPro = i % 2 === 1;
        rows.push({
            contentId,
            contentType: isPro ? content_1.CONTENT_TYPE_PROFESSIONAL : content_1.CONTENT_TYPE_LIFE,
            title: `示例帖子 ${i + 1}`,
            content: `这是用户 ${userId} 的 Mock 示例内容，便于验收列表展示。`,
            publishUserId: userId,
            nickName,
            avatarUrl: '',
            images: [],
            liked: 1 + i,
            commentCount: i,
            collectCount: 0,
            createTime: '2026-05-13 12:00:00',
            ...(isPro ? { answerCount: 2 } : {}),
        });
    }
    return rows;
}
function resolveMockPublicContents(userId) {
    const matched = collectAllMockContents().filter((c) => Number(c.publishUserId) === userId);
    if (matched.length > 0) {
        return matched.sort((a, b) => Number(b.contentId) - Number(a.contentId));
    }
    return mockSyntheticPublicContents(userId);
}
function buildMockUserProfileVO(userId) {
    const selfId = user_notification_mock_1.MOCK_USER_INFO.userId === undefined || user_notification_mock_1.MOCK_USER_INFO.userId === null
        ? 0
        : Number(user_notification_mock_1.MOCK_USER_INFO.userId);
    const list = resolveMockPublicContents(userId);
    const first = list[0];
    const nick = (first?.nickName && String(first.nickName).trim()) ||
        (userId >= 8000 && userId < 8010
            ? `生活用户${(userId % 4) + 1}`
            : userId >= 9000 && userId < 9010
                ? `答主${(userId % 3) + 1}`
                : `用户${userId}`);
    const followerBase = 8 + (userId % 89);
    const adjust = (0, follow_service_1.getMockFollowerAdjust)(userId);
    return {
        userId,
        nickName: nick,
        avatarUrl: first?.avatarUrl ?? '',
        authStatus: userId % 5 === 0 ? 2 : userId % 4,
        quantaDepartment: `部门${(userId % 7) + 1}`,
        quantaBatch: `${2000 + (userId % 5)}届`,
        contentCount: list.length,
        followingCount: 20 + (userId % 40),
        followerCount: Math.max(0, followerBase + adjust),
        isFollowed: (0, follow_service_1.getMockFollowedForUser)(userId),
        isSelf: selfId > 0 && userId === selfId,
    };
}
function pageSlice(all, current, size) {
    const pageNum = current < 1 ? 1 : current;
    const pageSize = size < 1 ? 10 : size;
    const start = (pageNum - 1) * pageSize;
    const slice = all.slice(start, start + pageSize);
    return { slice, hasMore: start + slice.length < all.length };
}
/** C 端公开用户主页 */
async function getUserProfile(userId) {
    if (!Number.isFinite(userId) || userId <= 0) {
        return Promise.resolve({
            ok: false,
            errorType: 'invalidData',
            message: '用户不存在或参数无效',
        });
    }
    if (request_1.USE_MOCK) {
        await (0, delay_1.mockDelay)();
        return { ok: true, data: buildMockUserProfileVO(userId) };
    }
    const res = await (0, request_2.request)({
        method: 'GET',
        url: `/user/${userId}/profile`,
    });
    if (!res.ok) {
        return res;
    }
    const data = res.data;
    if (data === undefined || data === null || typeof data !== 'object') {
        return { ok: false, errorType: 'invalidData', message: '用户主页数据异常' };
    }
    return { ok: true, data };
}
/** 用户公开内容分页（卡片模型） */
async function getUserPublicContents(userId, current, size) {
    if (!Number.isFinite(userId) || userId <= 0) {
        return Promise.resolve({
            ok: false,
            errorType: 'invalidData',
            message: '用户不存在或参数无效',
        });
    }
    if (request_1.USE_MOCK) {
        await (0, delay_1.mockDelay)();
        const all = resolveMockPublicContents(userId);
        const { slice, hasMore } = pageSlice(all, current, size);
        const list = [];
        for (const vo of slice) {
            const card = (0, content_service_1.mapContentVOToCard)(vo);
            if (card) {
                list.push(card);
            }
        }
        return { ok: true, data: { list, hasMore } };
    }
    const res = await (0, request_2.request)({
        method: 'GET',
        url: `/user/${userId}/contents`,
        data: {
            current: current < 1 ? 1 : current,
            size: size < 1 ? 10 : size,
        },
    });
    if (!res.ok) {
        return res;
    }
    const page = res.data;
    if (!page || !Array.isArray(page.list)) {
        return { ok: false, errorType: 'invalidData', message: '用户内容列表数据异常' };
    }
    const list = [];
    for (const vo of page.list) {
        const card = (0, content_service_1.mapContentVOToCard)(vo);
        if (card) {
            list.push(card);
        }
    }
    let hasMore = page.hasMore === true;
    if (page.hasMore !== true && page.hasMore !== false) {
        const total = page.total === undefined || page.total === null ? undefined : Number(page.total);
        const pageNum = page.pageNum === undefined || page.pageNum === null ? current : Number(page.pageNum);
        const pageSize = page.pageSize === undefined || page.pageSize === null ? size : Number(page.pageSize);
        if (total !== undefined && Number.isFinite(total) && Number.isFinite(pageNum) && Number.isFinite(pageSize)) {
            hasMore = pageNum * pageSize < total;
        }
        else {
            hasMore = list.length >= (size < 1 ? 10 : size);
        }
    }
    return { ok: true, data: { list, hasMore } };
}
