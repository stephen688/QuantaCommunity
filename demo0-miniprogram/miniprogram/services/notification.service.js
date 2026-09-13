"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.mapNotificationPageToItems = mapNotificationPageToItems;
exports.getUnreadCount = getUnreadCount;
exports.getNotificationList = getNotificationList;
exports.markNotificationRead = markNotificationRead;
exports.markAllNotificationsRead = markAllNotificationsRead;
const request_1 = require("../constants/request");
const user_notification_mock_1 = require("../mock/user-notification.mock");
const delay_1 = require("../mock/delay");
function notificationIconKind(type) {
    const t = (type || '').toUpperCase();
    if (t.includes('SYSTEM') ||
        t.includes('AUDIT') ||
        t.includes('SECURITY') ||
        t.includes('ACTIVITY')) {
        return 'system';
    }
    if (t.includes('COMMENT') ||
        t.includes('LIKE') ||
        t.includes('COLLECT') ||
        t.includes('FOLLOW')) {
        return 'interact';
    }
    return 'default';
}
const request_2 = require("../utils/request");
function mapNotificationVO(vo) {
    const id = vo.id === undefined || vo.id === null ? NaN : Number(vo.id);
    if (!Number.isFinite(id)) {
        return null;
    }
    const read = vo.isRead === 1;
    return {
        id,
        type: vo.type ?? '',
        typeDesc: vo.typeDesc ?? '',
        content: vo.content ?? '',
        read,
        createTime: vo.createTime === undefined || vo.createTime === null ? undefined : String(vo.createTime),
        payload: vo.payload ?? undefined,
        actorUserId: vo.actorUserId === undefined || vo.actorUserId === null ? undefined : Number(vo.actorUserId),
        iconKind: notificationIconKind(vo.type ?? ''),
    };
}
function mapNotificationPageToItems(page) {
    const raw = page.list ?? [];
    const out = [];
    for (const vo of raw) {
        const item = mapNotificationVO(vo);
        if (item) {
            out.push(item);
        }
    }
    return out;
}
async function getUnreadCount() {
    if (request_1.USE_MOCK) {
        await (0, delay_1.mockDelay)();
        const n = (0, user_notification_mock_1.getMockNotificationUnreadCount)();
        if (typeof n !== 'number' || Number.isNaN(n)) {
            return { ok: false, errorType: 'invalidData', message: '未读数格式异常' };
        }
        return { ok: true, data: Math.max(0, Math.floor(n)) };
    }
    const res = await (0, request_2.request)({
        method: 'GET',
        url: '/notification/unreadCount',
    });
    if (!res.ok) {
        return res;
    }
    const n = res.data;
    if (typeof n !== 'number' || Number.isNaN(n)) {
        return { ok: false, errorType: 'invalidData', message: '未读数格式异常' };
    }
    return { ok: true, data: Math.max(0, Math.floor(n)) };
}
async function getNotificationList(page = 1, pageSize = 10) {
    if (request_1.USE_MOCK) {
        await (0, delay_1.mockDelay)();
        const p = page < 1 ? 1 : page;
        const s = pageSize < 1 ? request_1.DEFAULT_PAGE_SIZE : pageSize;
        const total = user_notification_mock_1.MOCK_NOTIFICATION_LIST.length;
        const start = (p - 1) * s;
        const slice = user_notification_mock_1.MOCK_NOTIFICATION_LIST.slice(start, start + s);
        const totalPage = total > 0 ? Math.ceil(total / s) : 0;
        const hasMore = totalPage > 0 ? p < totalPage : false;
        return {
            ok: true,
            data: {
                list: slice.map((x) => ({ ...x })),
                total,
                pageNum: p,
                pageSize: s,
                totalPage,
                hasMore,
            },
        };
    }
    const p = page < 1 ? 1 : page;
    const s = pageSize < 1 ? 10 : pageSize;
    return (0, request_2.request)({
        method: 'GET',
        url: '/notification/list',
        data: { page: p, pageSize: s },
    });
}
async function markNotificationRead(id) {
    if (request_1.USE_MOCK) {
        await (0, delay_1.mockDelay)();
        if (!Number.isFinite(id) || id <= 0) {
            return { ok: false, errorType: 'invalidData', message: '通知不存在或参数无效' };
        }
        const row = user_notification_mock_1.MOCK_NOTIFICATION_LIST.find((x) => Number(x.id) === id);
        if (row) {
            row.isRead = 1;
        }
        return { ok: true, data: undefined };
    }
    if (!Number.isFinite(id) || id <= 0) {
        return { ok: false, errorType: 'invalidData', message: '通知不存在或参数无效' };
    }
    return (0, request_2.request)({
        method: 'PUT',
        url: `/notification/read/${id}`,
    });
}
async function markAllNotificationsRead() {
    if (request_1.USE_MOCK) {
        await (0, delay_1.mockDelay)();
        for (const row of user_notification_mock_1.MOCK_NOTIFICATION_LIST) {
            row.isRead = 1;
        }
        return { ok: true, data: undefined };
    }
    return (0, request_2.request)({
        method: 'PUT',
        url: '/notification/readAll',
    });
}
