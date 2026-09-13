import type { UserInfoVO } from '../types/user';

/**
 * 跨页面数据变更标记：Tab/栈内页面 onShow 时对比 revision，必要时重新拉取。
 * - userProfile：昵称、头像（/user/info/update）
 * - userAuth：实名认证状态与详情
 * - myContent：我的发布/点赞/收藏/浏览历史等列表
 */
export type AppRefreshScope = 'userProfile' | 'userAuth' | 'myContent';

const revisions: Record<AppRefreshScope, number> = {
  userProfile: 0,
  userAuth: 0,
  myContent: 0,
};

let cachedUserInfo: UserInfoVO | null = null;

export function getAppRefreshRevision(scope: AppRefreshScope): number {
  return revisions[scope];
}

export function bumpAppRefresh(...scopes: AppRefreshScope[]): void {
  for (const s of scopes) {
    revisions[s] += 1;
  }
}

export function shouldRefreshSince(scope: AppRefreshScope, seenRevision: number): boolean {
  return revisions[scope] > seenRevision;
}

export function getCachedUserInfo(): UserInfoVO | null {
  return cachedUserInfo;
}

export function setCachedUserInfo(info: UserInfoVO | null): void {
  cachedUserInfo = info ? { ...info } : null;
}

/** 合并最新资料到内存缓存（不增加 revision） */
export function mergeCachedUserInfo(patch: Partial<UserInfoVO>): void {
  if (!patch || typeof patch !== 'object') {
    return;
  }
  cachedUserInfo = { ...(cachedUserInfo ?? {}), ...patch };
}

export type PageRefreshSnapshot = Record<AppRefreshScope, number>;

export function snapshotPageRefresh(): PageRefreshSnapshot {
  return {
    userProfile: revisions.userProfile,
    userAuth: revisions.userAuth,
    myContent: revisions.myContent,
  };
}

/**
 * 若指定 scope 有更新，将 snapshot 推进到当前 revision 并返回 true。
 */
export function consumeAppRefresh(
  snapshot: PageRefreshSnapshot,
  scopes: AppRefreshScope[],
): boolean {
  let dirty = false;
  for (const scope of scopes) {
    if (shouldRefreshSince(scope, snapshot[scope])) {
      dirty = true;
      snapshot[scope] = revisions[scope];
    }
  }
  return dirty;
}
