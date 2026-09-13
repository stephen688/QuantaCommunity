import {
  DEVTOOLS_USE_REMOTE,
  LOCAL_BASE_URL,
  REMOTE_BASE_URL,
  REQUEST_TIMEOUT,
  TOKEN_KEY,
} from '../constants/request';
import type { ApiErrorType, ApiResult, RequestOptions } from '../types/api';
import { navigateToLoginPage } from './auth-nav';
import { clearToken, getToken } from './storage';

export interface RequestSuccess<T> {
  ok: true;
  data: T;
}

export interface RequestFailure {
  ok: false;
  errorType: ApiErrorType;
  message: string;
  statusCode?: number;
  retryAfter?: number;
}

export type RequestResult<T> = RequestSuccess<T> | RequestFailure;

/** 开发者工具默认本机 9191；DEVTOOLS_USE_REMOTE=true 时与真机同走 ngrok */
export function resolveBaseUrl(): string {
  try {
    const sys = wx.getSystemInfoSync();
    if (sys.platform === 'devtools' && !DEVTOOLS_USE_REMOTE) {
      return LOCAL_BASE_URL;
    }
  } catch {
    /* 非小程序环境 */
  }
  return REMOTE_BASE_URL;
}

function joinUrl(path: string): string {
  if (/^https?:\/\//i.test(path)) {
    return path;
  }
  const base = resolveBaseUrl().replace(/\/$/, '');
  const p = path.startsWith('/') ? path : `/${path}`;
  return `${base}${p}`;
}

function showToastIfNeeded(showErrorToast: boolean | undefined, message: string): void {
  if (showErrorToast) {
    wx.showToast({ title: message.slice(0, 14) || '请求失败', icon: 'none' });
  }
}

function readApiMessage(res: WechatMiniprogram.RequestSuccessCallbackResult): string {
  const body = res.data as unknown as ApiResult<unknown> | null | undefined;
  if (body && typeof body === 'object' && typeof body.msg === 'string') {
    return body.msg || '';
  }
  return '';
}

function readRetryAfterSeconds(res: WechatMiniprogram.RequestSuccessCallbackResult): number | undefined {
  const header = (res.header || {}) as Record<string, string | number | undefined>;
  const raw = header['retry-after'] ?? header['Retry-After'];
  const parsed = Number(raw);
  return Number.isFinite(parsed) && parsed > 0 ? parsed : undefined;
}

export function request<T>(options: RequestOptions): Promise<RequestResult<T>> {
  const { method = 'GET', url, data, header = {}, showErrorToast = false } = options;
  const token = getToken();
  const mergedHeader: Record<string, string> = {
    'ngrok-skip-browser-warning': '1',
    ...header,
  };
  if (token) {
    mergedHeader[TOKEN_KEY] = token;
  }

  return new Promise((resolve) => {
    wx.request({
      url: joinUrl(url),
      method,
      data,
      header: mergedHeader,
      timeout: REQUEST_TIMEOUT,
      success(res) {
        const status = res.statusCode;
        if (status === 401) {
          clearToken();
          navigateToLoginPage();
          const msg = '登录已失效，请重新登录';
          showToastIfNeeded(showErrorToast, msg);
          resolve({ ok: false, errorType: 'unauthorized', message: msg, statusCode: status });
          return;
        }
        if (status === 403) {
          const msg = readApiMessage(res) || '无权限执行该操作';
          showToastIfNeeded(showErrorToast, msg);
          resolve({ ok: false, errorType: 'forbidden', message: msg, statusCode: status });
          return;
        }
        if (status === 429) {
          const retryAfter = readRetryAfterSeconds(res);
          const suffix = retryAfter ? `，请 ${retryAfter} 秒后重试` : '，请稍后再试';
          const msg = (readApiMessage(res) || '操作过于频繁') + suffix;
          showToastIfNeeded(showErrorToast, msg);
          resolve({ ok: false, errorType: 'rateLimited', message: msg, statusCode: status, retryAfter });
          return;
        }
        if (status < 200 || status >= 300) {
          const msg = `网络异常(${status})`;
          showToastIfNeeded(showErrorToast, msg);
          resolve({ ok: false, errorType: 'network', message: msg, statusCode: status });
          return;
        }

        const body = res.data as unknown;
        if (body === null || body === undefined || typeof body !== 'object') {
          const msg = '数据格式异常';
          showToastIfNeeded(showErrorToast, msg);
          resolve({ ok: false, errorType: 'invalidData', message: msg, statusCode: status });
          return;
        }

        const result = body as ApiResult<T>;
        const code = result.code;
        if (code === 401) {
          clearToken();
          navigateToLoginPage();
          const msg = result.msg || '登录已失效';
          showToastIfNeeded(showErrorToast, msg);
          resolve({ ok: false, errorType: 'unauthorized', message: msg, statusCode: status });
          return;
        }
        if (code === 403) {
          const msg = result.msg || '无权限执行该操作';
          showToastIfNeeded(showErrorToast, msg);
          resolve({ ok: false, errorType: 'forbidden', message: msg, statusCode: status });
          return;
        }
        if (code === 429) {
          const retryAfter = readRetryAfterSeconds(res);
          const suffix = retryAfter ? `，请 ${retryAfter} 秒后重试` : '，请稍后再试';
          const msg = (result.msg || '操作过于频繁') + suffix;
          showToastIfNeeded(showErrorToast, msg);
          resolve({ ok: false, errorType: 'rateLimited', message: msg, statusCode: status, retryAfter });
          return;
        }
        if (code !== 200) {
          const msg = result.msg || '服务异常';
          showToastIfNeeded(showErrorToast, msg);
          resolve({ ok: false, errorType: 'server', message: msg, statusCode: status });
          return;
        }

        resolve({ ok: true, data: result.data as T });
      },
      fail(err) {
        const msg = err.errMsg?.includes('timeout') ? '请求超时' : '网络不可用';
        showToastIfNeeded(showErrorToast, msg);
        resolve({ ok: false, errorType: 'network', message: msg });
      },
    });
  });
}
