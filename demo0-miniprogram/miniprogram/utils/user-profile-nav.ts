import { ROUTES } from '../constants/route';

function normalizeUserId(raw: unknown): number {
  if (raw === undefined || raw === null || raw === '') {
    return NaN;
  }
  if (typeof raw === 'number') {
    return Number.isFinite(raw) ? raw : NaN;
  }
  if (typeof raw === 'string') {
    const n = Number(raw.trim());
    return Number.isFinite(n) ? n : NaN;
  }
  const n = Number(raw);
  return Number.isFinite(n) ? n : NaN;
}

/** 跳转用户主页；兼容 number / string（接口或组件透传可能为字符串） */
export function navigateToUserProfile(userId: unknown): void {
  const uid = normalizeUserId(userId);
  if (!Number.isFinite(uid) || uid <= 0 || !Number.isInteger(uid)) {
    wx.showToast({ title: '用户信息暂不可用', icon: 'none' });
    return;
  }
  wx.navigateTo({ url: `${ROUTES.USER_PROFILE}?userId=${uid}` });
}

export function navigateToUserProfileFromEvent(
  e: WechatMiniprogram.CustomEvent<{ userId?: number | string }>,
): void {
  navigateToUserProfile(e.detail?.userId);
}
