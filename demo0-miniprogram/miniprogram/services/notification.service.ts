import { DEFAULT_PAGE_SIZE, USE_MOCK } from '../constants/request';
import {
  getMockNotificationUnreadCount,
  MOCK_NOTIFICATION_LIST,
} from '../mock/user-notification.mock';
import { mockDelay } from '../mock/delay';
import type { PageVO } from '../types/api';
import type {
  NotificationIconKind,
  NotificationItemModel,
  NotificationVO,
} from '../types/notification';

function notificationIconKind(type: string): NotificationIconKind {
  const t = (type || '').toUpperCase();
  if (
    t.includes('SYSTEM') ||
    t.includes('AUDIT') ||
    t.includes('SECURITY') ||
    t.includes('ACTIVITY')
  ) {
    return 'system';
  }
  if (
    t.includes('COMMENT') ||
    t.includes('LIKE') ||
    t.includes('COLLECT') ||
    t.includes('FOLLOW')
  ) {
    return 'interact';
  }
  return 'default';
}
import { request, type RequestResult } from '../utils/request';

function mapNotificationVO(vo: NotificationVO): NotificationItemModel | null {
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
    createTime:
      vo.createTime === undefined || vo.createTime === null ? undefined : String(vo.createTime),
    payload: vo.payload ?? undefined,
    actorUserId:
      vo.actorUserId === undefined || vo.actorUserId === null ? undefined : Number(vo.actorUserId),
    iconKind: notificationIconKind(vo.type ?? ''),
  };
}

export function mapNotificationPageToItems(page: PageVO<NotificationVO>): NotificationItemModel[] {
  const raw = page.list ?? [];
  const out: NotificationItemModel[] = [];
  for (const vo of raw) {
    const item = mapNotificationVO(vo);
    if (item) {
      out.push(item);
    }
  }
  return out;
}

export async function getUnreadCount(): Promise<RequestResult<number>> {
  if (USE_MOCK) {
    await mockDelay();
    const n = getMockNotificationUnreadCount();
    if (typeof n !== 'number' || Number.isNaN(n)) {
      return { ok: false, errorType: 'invalidData', message: '未读数格式异常' };
    }
    return { ok: true, data: Math.max(0, Math.floor(n)) };
  }

  const res = await request<number>({
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

export async function getNotificationList(
  page: number = 1,
  pageSize: number = 10,
): Promise<RequestResult<PageVO<NotificationVO>>> {
  if (USE_MOCK) {
    await mockDelay();
    const p = page < 1 ? 1 : page;
    const s = pageSize < 1 ? DEFAULT_PAGE_SIZE : pageSize;
    const total = MOCK_NOTIFICATION_LIST.length;
    const start = (p - 1) * s;
    const slice = MOCK_NOTIFICATION_LIST.slice(start, start + s);
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
  return request<PageVO<NotificationVO>>({
    method: 'GET',
    url: '/notification/list',
    data: { page: p, pageSize: s },
  });
}

export async function markNotificationRead(id: number): Promise<RequestResult<void>> {
  if (USE_MOCK) {
    await mockDelay();
    if (!Number.isFinite(id) || id <= 0) {
      return { ok: false, errorType: 'invalidData', message: '通知不存在或参数无效' };
    }
    const row = MOCK_NOTIFICATION_LIST.find((x) => Number(x.id) === id);
    if (row) {
      row.isRead = 1;
    }
    return { ok: true, data: undefined as void };
  }
  if (!Number.isFinite(id) || id <= 0) {
    return { ok: false, errorType: 'invalidData', message: '通知不存在或参数无效' };
  }
  return request<void>({
    method: 'PUT',
    url: `/notification/read/${id}`,
  });
}

export async function markAllNotificationsRead(): Promise<RequestResult<void>> {
  if (USE_MOCK) {
    await mockDelay();
    for (const row of MOCK_NOTIFICATION_LIST) {
      row.isRead = 1;
    }
    return { ok: true, data: undefined as void };
  }
  return request<void>({
    method: 'PUT',
    url: '/notification/readAll',
  });
}
