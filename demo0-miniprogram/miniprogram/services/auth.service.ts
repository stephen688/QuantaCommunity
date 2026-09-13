import { DEV_LOGIN_CODE } from '../constants/request';
import type { UserLoginVO } from '../types/user';
import {
  hideLoginSheet,
} from '../utils/login-sheet-bus';
import { navigateToLoginPage } from '../utils/auth-nav';
import { bumpAppRefresh, setCachedUserInfo } from '../utils/app-refresh-bus';
import { request, type RequestResult } from '../utils/request';
import { clearLoginUserId, clearToken, getToken, setLoginUserId, setToken } from '../utils/storage';

const TOURIST_APP_ID = 'touristappid';

function shouldUseDevLoginCode(): boolean {
  try {
    const account = wx.getAccountInfoSync();
    return account.miniProgram.appId === TOURIST_APP_ID;
  } catch {
    return true;
  }
}

function wxLoginCode(): Promise<string> {
  return new Promise((resolve, reject) => {
    wx.login({
      timeout: 10000,
      success(res) {
        if (res.code) {
          resolve(res.code);
          return;
        }
        reject(new Error('wx.login 未返回 code'));
      },
      fail(err) {
        reject(err ?? new Error('wx.login 失败'));
      },
    });
  });
}

function isMockWxLoginCode(code: string): boolean {
  return /mock/i.test(code);
}

async function resolveLoginCode(): Promise<string> {
  if (shouldUseDevLoginCode()) {
    return DEV_LOGIN_CODE;
  }
  const code = await wxLoginCode();
  // 开发者工具模拟器即使用真实 AppID，wx.login 也会返回 mock code，无法调微信 jscode2session
  if (isMockWxLoginCode(code)) {
    return DEV_LOGIN_CODE;
  }
  return code;
}

export type WeChatProfilePayload = {
  nickName?: string;
  avatarUrl?: string;
};

function cacheLoginUser(login: UserLoginVO): void {
  if (!login || typeof login !== 'object') {
    return;
  }
  const userId = login.id === undefined || login.id === null ? undefined : Number(login.id);
  if (Number.isFinite(userId) && Number(userId) > 0) {
    setLoginUserId(Number(userId));
  }
  setCachedUserInfo({
    userId: Number.isFinite(userId) ? userId : undefined,
    nickName: login.nickName,
    avatarUrl: login.avatarUrl,
  });
  bumpAppRefresh('userProfile');
}

export async function loginWithCode(
  code: string,
  profile?: WeChatProfilePayload,
): Promise<RequestResult<UserLoginVO>> {
  const data: { code: string; nickName?: string; avatarUrl?: string } = { code };
  const nickName = profile?.nickName?.trim();
  const avatarUrl = profile?.avatarUrl?.trim();
  if (nickName) {
    data.nickName = nickName;
  }
  if (avatarUrl) {
    data.avatarUrl = avatarUrl;
  }
  return request<UserLoginVO>({
    method: 'POST',
    url: '/user/login',
    data,
  });
}

export function isLoggedIn(): boolean {
  return Boolean(getToken());
}

/** 微信一键登录：wx.login（或联调 mock code）换 JWT；可附带授权后的昵称/头像 */
export async function loginWithWeChat(profile?: WeChatProfilePayload): Promise<boolean> {
  try {
    const code = await resolveLoginCode();
    const res = await loginWithCode(code, profile);
    if (res.ok && res.data?.token) {
      setToken(res.data.token);
      cacheLoginUser(res.data);
      hideLoginSheet();
      return true;
    }
    const msg = res.ok ? '登录失败，请重试' : res.message || '登录失败';
    wx.showToast({ title: msg.slice(0, 14), icon: 'none' });
    return false;
  } catch {
    wx.showToast({ title: '微信登录失败', icon: 'none' });
    return false;
  }
}

/**
 * 未登录时跳转登录页（不弹出资料授权层）。
 */
export function promptLoginIfNeeded(): void {
  if (isLoggedIn()) {
    return;
  }
  navigateToLoginPage();
}

export async function ensureLoggedIn(options?: { silent?: boolean }): Promise<boolean> {
  if (isLoggedIn()) {
    return true;
  }
  if (!options?.silent) {
    navigateToLoginPage();
  }
  return false;
}

export function logout(): void {
  clearLoginUserId();
  clearToken();
}
