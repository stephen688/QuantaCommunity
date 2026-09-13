import { REQUEST_TIMEOUT, TOKEN_KEY } from '../constants/request';
import type { ApiResult } from '../types/api';
import { clearToken, getToken } from '../utils/storage';
import { navigateToLoginPage } from '../utils/auth-nav';
import { resolveBaseUrl, type RequestResult } from '../utils/request';

function joinUrl(path: string): string {
  const base = resolveBaseUrl().replace(/\/$/, '');
  const p = path.startsWith('/') ? path : `/${path}`;
  return `${base}${p}`;
}

function parseUploadBody<T>(raw: string): RequestResult<T> {
  let body: unknown;
  try {
    body = JSON.parse(raw) as unknown;
  } catch {
    return { ok: false, errorType: 'invalidData', message: '上传响应解析失败' };
  }
  if (body === null || typeof body !== 'object') {
    return { ok: false, errorType: 'invalidData', message: '上传数据格式异常' };
  }
  const result = body as ApiResult<T>;
  if (result.code === 401) {
    return { ok: false, errorType: 'unauthorized', message: result.msg || '登录已失效' };
  }
  if (result.code !== 200) {
    return { ok: false, errorType: 'server', message: result.msg || '上传失败' };
  }
  const data = result.data as T;
  if (data === undefined || data === null) {
    return { ok: false, errorType: 'invalidData', message: '未返回图片地址' };
  }
  return { ok: true, data };
}

const MAX_UPLOAD_BYTES = 10 * 1024 * 1024;

function compressImageForUpload(filePath: string): Promise<string> {
  return new Promise((resolve) => {
    wx.compressImage({
      src: filePath,
      quality: 80,
      width: 1920,
      success(res) {
        resolve(res.tempFilePath || filePath);
      },
      fail() {
        resolve(filePath);
      },
    });
  });
}

function uploadFile(filePath: string, token: string): Promise<RequestResult<string>> {
  const targetUrl = joinUrl('/common/upload');
  const header: Record<string, string> = {
    'ngrok-skip-browser-warning': '1',
    [TOKEN_KEY]: token,
  };
  console.log('[upload] targetUrl:', targetUrl);
  console.log('[upload] header key:', TOKEN_KEY, 'value length:', token?.length);
  return new Promise((resolve) => {
    wx.uploadFile({
      url: targetUrl,
      filePath,
      name: 'file',
      header,
      timeout: REQUEST_TIMEOUT,
      success(res) {
        console.log('[upload] success status:', res.statusCode, 'data:', res.data);
        const status = res.statusCode;
        if (status === 401) {
          clearToken();
          navigateToLoginPage();
          resolve({ ok: false, errorType: 'unauthorized', message: '登录已失效，请重新登录', statusCode: status });
          return;
        }
        const raw = typeof res.data === 'string' ? res.data : '';
        if (status < 200 || status >= 300) {
          const parsed = raw ? parseUploadBody<string>(raw) : null;
          const message =
            parsed && !parsed.ok && parsed.message
              ? parsed.message
              : status === 400
                ? '图片大小不能超过10MB'
                : `网络异常(${status})`;
          resolve({
            ok: false,
            errorType: status === 400 ? 'server' : 'network',
            message,
            statusCode: status,
          });
          return;
        }
        resolve(parseUploadBody<string>(raw));
      },
      fail(err) {
        console.log('[upload] fail errMsg:', err.errMsg);
        const msg = err.errMsg?.includes('timeout') ? '请求超时' : '网络不可用';
        resolve({ ok: false, errorType: 'network', message: msg });
      },
    });
  });
}

function getLocalFileSize(filePath: string): Promise<number | null> {
  return new Promise((resolve) => {
    wx.getFileSystemManager().getFileInfo({
      filePath,
      success(res) {
        resolve(res.size);
      },
      fail: () => resolve(null),
    });
  });
}

/**
 * 上传单张图片到 `/common/upload`，字段名 `file`。
 */
export async function uploadImage(filePath: string): Promise<RequestResult<string>> {
  if (typeof filePath !== 'string' || !filePath.trim()) {
    return {
      ok: false,
      errorType: 'invalidData',
      message: '请选择有效图片',
    };
  }
  const token = getToken();
  if (!token) {
    navigateToLoginPage();
    return {
      ok: false,
      errorType: 'unauthorized',
      message: '请先登录后再上传图片',
    };
  }
  console.log('[upload] resolveBaseUrl:', resolveBaseUrl(), 'platform:', (wx.getSystemInfoSync?.() as { platform?: string } | undefined)?.platform);
  const preparedPath = await compressImageForUpload(filePath.trim());
  const fileSize = await getLocalFileSize(preparedPath);
  if (fileSize !== null && fileSize > MAX_UPLOAD_BYTES) {
    return {
      ok: false,
      errorType: 'invalidData',
      message: '图片大小不能超过10MB',
    };
  }
  return uploadFile(preparedPath, token);
}
