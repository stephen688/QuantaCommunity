import { TOKEN_KEY } from '../constants/request';

const LOGIN_USER_ID_KEY = 'LOGIN_USER_ID';

export function getToken(): string | undefined {
  try {
    const v = wx.getStorageSync(TOKEN_KEY) as unknown;
    if (typeof v !== 'string' || !v.trim()) {
      return undefined;
    }
    return v.trim();
  } catch {
    return undefined;
  }
}

export function setToken(token: string): void {
  wx.setStorageSync(TOKEN_KEY, token);
}

export function clearToken(): void {
  try {
    wx.removeStorageSync(TOKEN_KEY);
  } catch {
    try {
      wx.setStorageSync(TOKEN_KEY, '');
    } catch {
      /* ignore */
    }
  }
}

export function getLoginUserId(): number | undefined {
  const raw = getStorageSafe<unknown>(LOGIN_USER_ID_KEY);
  const n = typeof raw === 'number' ? raw : Number(raw);
  if (Number.isFinite(n) && n > 0) {
    return Math.trunc(n);
  }
  const fromToken = extractUserIdFromToken(getToken());
  if (fromToken !== undefined) {
    setLoginUserId(fromToken);
  }
  return fromToken;
}

export function setLoginUserId(userId: number): void {
  if (!Number.isFinite(userId) || userId <= 0) {
    return;
  }
  setStorageSafe(LOGIN_USER_ID_KEY, Math.trunc(userId));
}

export function clearLoginUserId(): void {
  try {
    wx.removeStorageSync(LOGIN_USER_ID_KEY);
  } catch {
    /* ignore */
  }
}

function extractUserIdFromToken(token: string | undefined): number | undefined {
  if (!token) {
    return undefined;
  }
  const parts = token.split('.');
  if (parts.length < 2 || !parts[1]) {
    return undefined;
  }
  const payload = decodeBase64UrlJson(parts[1]);
  if (!payload || typeof payload !== 'object') {
    return undefined;
  }
  const uidRaw = (payload as Record<string, unknown>).userId;
  const uid = typeof uidRaw === 'number' ? uidRaw : Number(uidRaw);
  if (!Number.isFinite(uid) || uid <= 0) {
    return undefined;
  }
  return Math.trunc(uid);
}

function decodeBase64UrlJson(segment: string): unknown {
  try {
    const normalized = normalizeBase64(segment);
    const raw = base64Decode(normalized);
    if (!raw) {
      return undefined;
    }
    return JSON.parse(raw) as unknown;
  } catch {
    return undefined;
  }
}

function normalizeBase64(segment: string): string {
  const replaced = segment.replace(/-/g, '+').replace(/_/g, '/');
  const padding = replaced.length % 4;
  if (padding === 0) {
    return replaced;
  }
  return `${replaced}${'='.repeat(4 - padding)}`;
}

function base64Decode(base64: string): string | undefined {
  try {
    const buffer = wx.base64ToArrayBuffer(base64);
    const bytes = new Uint8Array(buffer);
    let out = '';
    for (let i = 0; i < bytes.length; i += 1) {
      out += String.fromCharCode(bytes[i]);
    }
    return out;
  } catch {
    return undefined;
  }
}

export function getStorageSafe<T>(key: string): T | undefined {
  try {
    return wx.getStorageSync(key) as T;
  } catch {
    return undefined;
  }
}

export function setStorageSafe(key: string, value: unknown): void {
  try {
    wx.setStorageSync(key, value);
  } catch {
    /* ignore quota / serialize errors */
  }
}
