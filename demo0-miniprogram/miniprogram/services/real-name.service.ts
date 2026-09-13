import { ROUTES } from '../constants/route';
import { getAuthStatus } from './user-auth.service';
import { getCachedUserInfo } from '../utils/app-refresh-bus';
import { showAuthInterceptModal } from '../utils/auth-intercept-bus';
import { ensureLoggedIn, isLoggedIn } from './auth.service';

export type RealNameCheckOptions = {
  silent?: boolean;
  title?: string;
  description?: string;
  reason?: string;
};

/**
 * 是否已通过实名认证。
 * - GET /user/auth/status：tb_user_auth.audit_status，1=已通过
 * - 主页 profile：tb_user.auth_status，2=已认证（与审核表 1 不是同一套枚举）
 */
export async function isRealNameVerified(): Promise<boolean> {
  const res = await getAuthStatus();
  if (res.ok && res.data?.auditStatus === 1) {
    return true;
  }
  const cached = getCachedUserInfo();
  if (cached?.authStatus === 2) {
    return true;
  }
  return false;
}

/**
 * 确保已完成实名认证。
 * 未登录时先唤起登录层；未认证时弹出引导弹窗。
 */
export async function ensureRealNameVerified(options?: RealNameCheckOptions): Promise<boolean> {
  if (!isLoggedIn()) {
    if (!options?.silent) {
      await ensureLoggedIn();
    }
    return false;
  }
  const verified = await isRealNameVerified();
  if (verified || options?.silent) {
    return verified;
  }
  showAuthInterceptModal({
    title: options?.title,
    description: options?.description,
    reason: options?.reason,
  });
  return false;
}

export function navigateToRealNameVerify(): void {
  wx.navigateTo({ url: `${ROUTES.PROFILE_EDIT}?intent=verify` });
}
