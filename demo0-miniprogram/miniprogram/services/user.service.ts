import { CONTENT_TYPE_LIFE, CONTENT_TYPE_PROFESSIONAL } from '../constants/content';
import { USE_MOCK } from '../constants/request';
import { mockDelay } from '../mock/delay';
import { getFollowMockPool } from '../mock/follow-pool';
import { getRecommendMockPool } from '../mock/recommend-pool';
import { MOCK_USER_INFO } from '../mock/user-notification.mock';
import type { PageVO } from '../types/api';
import type { ContentVO } from '../types/content';
import type {
  UserInfoDTO,
  UserInfoVO,
  UserProfileModel,
  UserProfileVO,
  UserPublicContentPage,
} from '../types/user';
import { userProfileAuthStatusText } from '../types/user';
import { bumpAppRefresh, mergeCachedUserInfo, setCachedUserInfo } from '../utils/app-refresh-bus';
import { request, type RequestResult } from '../utils/request';
import { getLoginUserId } from '../utils/storage';
import { mapContentVOToCard } from './content.service';
import { getMockFollowerAdjust, getMockFollowedForUser } from './follow.service';

export async function getUserInfo(): Promise<RequestResult<UserInfoVO>> {
  if (USE_MOCK) {
    await mockDelay();
    return { ok: true, data: { ...MOCK_USER_INFO } };
  }

  const res = await request<UserInfoVO>({
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
  const normalized: UserInfoVO = { ...data };
  const rawUserId = normalized.userId === undefined || normalized.userId === null
    ? NaN
    : Number(normalized.userId);
  if (!Number.isFinite(rawUserId) || rawUserId <= 0) {
    const cachedUserId = getLoginUserId();
    if (cachedUserId !== undefined) {
      normalized.userId = cachedUserId;
    }
  }
  setCachedUserInfo(normalized);
  return { ok: true, data: normalized };
}

export async function updateUserInfo(payload: UserInfoDTO): Promise<RequestResult<void>> {
  if (USE_MOCK) {
    await mockDelay();
    return { ok: true, data: undefined as void };
  }
  const res = await request<void>({
    method: 'PUT',
    url: '/user/info/update',
    data: { ...payload },
  });
  if (res.ok) {
    mergeCachedUserInfo({
      nickName: payload.nickName,
      avatarUrl: payload.avatarUrl,
    });
    bumpAppRefresh('userProfile');
  }
  return res;
}

/** 认证表 audit_status=1（已通过）时，主页展示态应为 2（已认证） */
export function resolveProfileDisplayAuthStatus(
  profileAuthStatus?: number | null,
  auditStatus?: number | null,
): number {
  if (auditStatus === 1) {
    return 2;
  }
  return profileAuthStatus ?? 0;
}

export function mapUserProfileVOToModel(
  vo: UserProfileVO,
  auditStatus?: number | null,
): UserProfileModel | null {
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
    authStatusText: userProfileAuthStatusText(authStatus),
    quantaDepartment: vo.quantaDepartment ?? '',
    quantaBatch: vo.quantaBatch ?? '',
    contentCount: vo.contentCount ?? 0,
    followingCount: vo.followingCount ?? 0,
    followerCount: vo.followerCount ?? 0,
    isFollowed: vo.isFollowed === true,
    isSelf: vo.isSelf === true,
  };
}

function collectAllMockContents(): ContentVO[] {
  const seen = new Set<number>();
  const out: ContentVO[] = [];
  const pushPool = (pool: ContentVO[]) => {
    for (const c of pool) {
      const id = Number(c.contentId);
      if (!Number.isFinite(id) || seen.has(id)) {
        continue;
      }
      seen.add(id);
      out.push(c);
    }
  };
  pushPool(getRecommendMockPool());
  pushPool(getFollowMockPool());
  pushPool(getFollowMockPool(CONTENT_TYPE_LIFE));
  pushPool(getFollowMockPool(CONTENT_TYPE_PROFESSIONAL));
  return out;
}

function mockSyntheticPublicContents(userId: number): ContentVO[] {
  const n = 2 + (userId % 2);
  const nickName = `用户${userId}`;
  const rows: ContentVO[] = [];
  for (let i = 0; i < n; i += 1) {
    const contentId = 6000000 + userId * 10 + i;
    const isPro = i % 2 === 1;
    rows.push({
      contentId,
      contentType: isPro ? CONTENT_TYPE_PROFESSIONAL : CONTENT_TYPE_LIFE,
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

function resolveMockPublicContents(userId: number): ContentVO[] {
  const matched = collectAllMockContents().filter((c) => Number(c.publishUserId) === userId);
  if (matched.length > 0) {
    return matched.sort((a, b) => Number(b.contentId) - Number(a.contentId));
  }
  return mockSyntheticPublicContents(userId);
}

function buildMockUserProfileVO(userId: number): UserProfileVO {
  const selfId = MOCK_USER_INFO.userId === undefined || MOCK_USER_INFO.userId === null
    ? 0
    : Number(MOCK_USER_INFO.userId);
  const list = resolveMockPublicContents(userId);
  const first = list[0];
  const nick =
    (first?.nickName && String(first.nickName).trim()) ||
    (userId >= 8000 && userId < 8010
      ? `生活用户${(userId % 4) + 1}`
      : userId >= 9000 && userId < 9010
        ? `答主${(userId % 3) + 1}`
        : `用户${userId}`);
  const followerBase = 8 + (userId % 89);
  const adjust = getMockFollowerAdjust(userId);
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
    isFollowed: getMockFollowedForUser(userId),
    isSelf: selfId > 0 && userId === selfId,
  };
}

function pageSlice<T>(all: T[], current: number, size: number): { slice: T[]; hasMore: boolean } {
  const pageNum = current < 1 ? 1 : current;
  const pageSize = size < 1 ? 10 : size;
  const start = (pageNum - 1) * pageSize;
  const slice = all.slice(start, start + pageSize);
  return { slice, hasMore: start + slice.length < all.length };
}

/** C 端公开用户主页 */
export async function getUserProfile(userId: number): Promise<RequestResult<UserProfileVO>> {
  if (!Number.isFinite(userId) || userId <= 0) {
    return Promise.resolve({
      ok: false,
      errorType: 'invalidData',
      message: '用户不存在或参数无效',
    });
  }
  if (USE_MOCK) {
    await mockDelay();
    return { ok: true, data: buildMockUserProfileVO(userId) };
  }
  const res = await request<UserProfileVO>({
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
export async function getUserPublicContents(
  userId: number,
  current: number,
  size: number,
): Promise<RequestResult<UserPublicContentPage>> {
  if (!Number.isFinite(userId) || userId <= 0) {
    return Promise.resolve({
      ok: false,
      errorType: 'invalidData',
      message: '用户不存在或参数无效',
    });
  }
  if (USE_MOCK) {
    await mockDelay();
    const all = resolveMockPublicContents(userId);
    const { slice, hasMore } = pageSlice(all, current, size);
    const list: UserPublicContentPage['list'] = [];
    for (const vo of slice) {
      const card = mapContentVOToCard(vo);
      if (card) {
        list.push(card);
      }
    }
    return { ok: true, data: { list, hasMore } };
  }
  const res = await request<PageVO<ContentVO>>({
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
  const list: UserPublicContentPage['list'] = [];
  for (const vo of page.list) {
    const card = mapContentVOToCard(vo);
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
    } else {
      hasMore = list.length >= (size < 1 ? 10 : size);
    }
  }
  return { ok: true, data: { list, hasMore } };
}
