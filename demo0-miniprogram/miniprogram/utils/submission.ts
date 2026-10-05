import { getLoginUserId } from './storage';

/**
 * 提交幂等本地状态：生成一次提交凭证、冻结原始 payload，并在网络结果未知时保留待确认记录。
 * 记录按登录用户隔离；本模块不发送 HTTP，也不自动重试业务请求。
 */

export type SubmissionScene = 'content-publish' | 'answer-publish' | 'comment-send';

export const SUBMISSION_RETENTION_MS = 7 * 24 * 60 * 60 * 1000;
export const MAX_PENDING_SUBMISSIONS = 20;

export interface PendingSubmission<T = unknown> {
  token: string;
  scene: SubmissionScene;
  payload: T;
  contextKey: string;
  createdAt: number;
  ownerUserId: number;
}

export type SubmissionPrepareFailureType =
  | 'missingUser'
  | 'storage'
  | 'uuid'
  | 'capacity'
  | 'expired'
  | 'accountChanged';

export type SubmissionPrepareResult<T> =
  | { ok: true; data: PendingSubmission<T> }
  | { ok: false; errorType: SubmissionPrepareFailureType; message: string };

type SubmissionStore = Record<string, PendingSubmission[]>;
type StorageReadResult = { ok: true; data: SubmissionStore } | { ok: false };

const STORAGE_KEY = 'HTTP_SUBMISSION_PENDING_V1';

function cloneAndFreeze<T>(value: T): T {
  if (value === null || typeof value !== 'object') {
    return value;
  }
  if (Array.isArray(value)) {
    const copy = value.map((item) => cloneAndFreeze(item)) as unknown as T;
    return Object.freeze(copy);
  }
  const source = value as Record<string, unknown>;
  const copy: Record<string, unknown> = {};
  for (const key of Object.keys(source)) {
    copy[key] = cloneAndFreeze(source[key]);
  }
  return Object.freeze(copy) as T;
}

function cloneRecord<T>(record: PendingSubmission<T>): PendingSubmission<T> {
  return {
    token: record.token,
    scene: record.scene,
    payload: cloneAndFreeze(record.payload),
    contextKey: record.contextKey,
    createdAt: record.createdAt,
    ownerUserId: record.ownerUserId,
  };
}

function readStore(): StorageReadResult {
  try {
    const raw = wx.getStorageSync(STORAGE_KEY) as unknown;
    if (raw === undefined || raw === null || raw === '') {
      return { ok: true, data: {} };
    }
    if (typeof raw !== 'object' || Array.isArray(raw)) {
      return { ok: false };
    }
    return { ok: true, data: raw as SubmissionStore };
  } catch {
    return { ok: false };
  }
}

function writeStore(store: SubmissionStore): boolean {
  try {
    wx.setStorageSync(STORAGE_KEY, store);
    return true;
  } catch {
    return false;
  }
}

function accountKey(): string | undefined {
  const userId = getLoginUserId();
  return userId === undefined ? undefined : String(userId);
}

/** 异步恢复或发送前确认记录仍属于当前登录账号，避免切换账号后展示/发送旧 payload。 */
export function isPendingSubmissionOwner(record: PendingSubmission): boolean {
  const key = accountKey();
  return key !== undefined && Number(record.ownerUserId) === Number(key);
}

/** 判断页面内存中的提交是否仍指向同一份冻结记录。 */
export function isSamePendingSubmission(
  current: PendingSubmission | null | undefined,
  record: Pick<PendingSubmission, 'scene' | 'contextKey' | 'token'>,
): boolean {
  return Boolean(
    current &&
      current.scene === record.scene &&
      current.contextKey === record.contextKey &&
      current.token === record.token,
  );
}

/** 恢复请求返回前确认记录仍属于当前账号，且 storage 中仍保留同一 token。 */
export function isPendingSubmissionCurrent(
  current: PendingSubmission | null | undefined,
  record: PendingSubmission,
): boolean {
  if (!isPendingSubmissionOwner(record) || !isSamePendingSubmission(current, record)) {
    return false;
  }
  const stored = getPendingSubmission(record.scene, record.contextKey);
  return Boolean(
    stored &&
      stored.token === record.token &&
      Number(stored.ownerUserId) === Number(record.ownerUserId),
  );
}

export type PendingSubmissionSendCheck =
  | { ok: true }
  | { ok: false; errorType: 'accountChanged' | 'expired'; message: string };

/** 实际 POST 前再次确认账号和 7 天窗口，避免页面驻留过期后用旧凭证重发。 */
export function validatePendingSubmissionForSend(
  record: PendingSubmission,
  now = Date.now(),
): PendingSubmissionSendCheck {
  if (!isPendingSubmissionOwner(record)) {
    return { ok: false, errorType: 'accountChanged', message: '登录状态已变化，请重新提交' };
  }
  if (isSubmissionExpired(record, now)) {
    clearPendingSubmission(record);
    return { ok: false, errorType: 'expired', message: '提交凭证已过期，请先核对发布记录' };
  }
  return { ok: true };
}

function recordsFor(store: SubmissionStore, key: string): PendingSubmission[] {
  const records = store[key];
  return Array.isArray(records) ? records : [];
}

function removeExpiredRecords(
  store: SubmissionStore,
  key: string,
  now: number,
): { ok: true; records: PendingSubmission[] } | { ok: false } {
  const records = recordsFor(store, key);
  const active = records.filter((record) => !isSubmissionExpired(record, now));
  if (active.length === records.length) {
    return { ok: true, records };
  }
  store[key] = active;
  return writeStore(store) ? { ok: true, records: active } : { ok: false };
}

/** 判断待确认记录是否已超过从首次准备发送起算的 7 天。 */
export function isSubmissionExpired(record: PendingSubmission, now = Date.now()): boolean {
  return !Number.isFinite(record.createdAt) || now - record.createdAt >= SUBMISSION_RETENTION_MS;
}

/** 使用微信安全随机源生成标准 UUID v4；随机源不可用时明确失败，不降级到时间戳或 Math.random。 */
export function createSubmissionToken(): Promise<string> {
  return new Promise((resolve, reject) => {
    try {
      if (typeof wx.getRandomValues !== 'function') {
        reject(new Error('wx.getRandomValues 不可用'));
        return;
      }
      wx.getRandomValues({
        length: 16,
        success(result) {
          const bytes = new Uint8Array(result.randomValues);
          if (bytes.length !== 16) {
            reject(new Error('随机字节长度异常'));
            return;
          }
          bytes[6] = (bytes[6] & 0x0f) | 0x40;
          bytes[8] = (bytes[8] & 0x3f) | 0x80;
          const hex = Array.from(bytes, (byte) => byte.toString(16).padStart(2, '0')).join('');
          resolve(
            `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`,
          );
        },
        fail(error) {
          reject(error instanceof Error ? error : new Error('随机凭证生成失败'));
        },
      });
    } catch (error) {
      reject(error instanceof Error ? error : new Error('随机凭证生成失败'));
    }
  });
}

/** 读取当前账号某个上下文的待确认提交；读取异常时返回 undefined，调用方不应据此发起未知请求。 */
export function getPendingSubmission<T = unknown>(
  scene: SubmissionScene,
  contextKey: string,
): PendingSubmission<T> | undefined {
  const key = accountKey();
  if (!key) {
    return undefined;
  }
  const stored = readStore();
  if (!stored.ok) {
    return undefined;
  }
  const found = recordsFor(stored.data, key).find(
    (record) => record.scene === scene && record.contextKey === contextKey,
  );
  return found ? cloneRecord(found) as PendingSubmission<T> : undefined;
}

/** 读取当前账号某个页面前缀下的记录，用于评论/回复页恢复未知上下文。 */
export function listPendingSubmissions<T = unknown>(
  scene: SubmissionScene,
  contextPrefix = '',
): PendingSubmission<T>[] {
  const key = accountKey();
  if (!key) {
    return [];
  }
  const stored = readStore();
  if (!stored.ok) {
    return [];
  }
  return recordsFor(stored.data, key)
    .filter((record) => record.scene === scene && record.contextKey.startsWith(contextPrefix))
    .map((record) => cloneRecord(record) as PendingSubmission<T>);
}

/**
 * 为一次新的业务操作创建或复用提交凭证。
 * 已存在且未过期的记录始终返回原 payload，防止用户编辑中的草稿覆盖未知结果。
 */
export async function prepareSubmission<T>(
  scene: SubmissionScene,
  contextKey: string,
  payload: T,
  now = Date.now(),
): Promise<SubmissionPrepareResult<T>> {
  const key = accountKey();
  if (!key) {
    return { ok: false, errorType: 'missingUser', message: '登录状态无效，请重新登录' };
  }
  const ownerUserId = Number(key);
  const frozenPayload = cloneAndFreeze(payload);
  const stored = readStore();
  if (!stored.ok) {
    return { ok: false, errorType: 'storage', message: '本地提交记录不可用，请稍后重试' };
  }

  const originalCurrent = recordsFor(stored.data, key);
  const existing = originalCurrent.find(
    (record) => record.scene === scene && record.contextKey === contextKey,
  );
  const existingExpired = existing ? isSubmissionExpired(existing, now) : false;
  const cleaned = removeExpiredRecords(stored.data, key, now);
  if (!cleaned.ok) {
    return { ok: false, errorType: 'storage', message: '本地提交记录不可用，请稍后重试' };
  }
  const current = cleaned.records;
  const activeExisting = current.find(
    (record) => record.scene === scene && record.contextKey === contextKey,
  );
  if (activeExisting) {
    return { ok: true, data: cloneRecord(activeExisting) as PendingSubmission<T> };
  }
  if (existingExpired) {
    return { ok: false, errorType: 'expired', message: '提交凭证已过期，请先核对发布记录' };
  }

  if (current.length >= MAX_PENDING_SUBMISSIONS) {
    return {
      ok: false,
      errorType: 'capacity',
      message: '待确认提交过多，请先处理已有提交',
    };
  }

  let token: string;
  try {
    token = await createSubmissionToken();
  } catch {
    return { ok: false, errorType: 'uuid', message: '无法生成提交凭证，请稍后重试' };
  }

  // UUID 生成是异步的；期间可能发生登录切换或另一份提交写入，必须重新读库并合并。
  if (accountKey() !== key) {
    return { ok: false, errorType: 'accountChanged', message: '登录状态已变化，请重新提交' };
  }
  const latest = readStore();
  if (!latest.ok) {
    return { ok: false, errorType: 'storage', message: '本地提交记录不可用，请稍后重试' };
  }
  const originalLatestRecords = recordsFor(latest.data, key);
  const latestExisting = originalLatestRecords.find(
    (record) => record.scene === scene && record.contextKey === contextKey,
  );
  const latestExistingExpired = latestExisting ? isSubmissionExpired(latestExisting, now) : false;
  const latestCleaned = removeExpiredRecords(latest.data, key, now);
  if (!latestCleaned.ok) {
    return { ok: false, errorType: 'storage', message: '本地提交记录不可用，请稍后重试' };
  }
  const latestRecords = latestCleaned.records;
  const latestActiveExisting = latestRecords.find(
    (record) => record.scene === scene && record.contextKey === contextKey,
  );
  if (latestActiveExisting) {
    return { ok: true, data: cloneRecord(latestActiveExisting) as PendingSubmission<T> };
  }
  if (latestExistingExpired) {
    return { ok: false, errorType: 'expired', message: '提交凭证已过期，请先核对发布记录' };
  }
  if (latestRecords.length >= MAX_PENDING_SUBMISSIONS) {
    return {
      ok: false,
      errorType: 'capacity',
      message: '待确认提交过多，请先处理已有提交',
    };
  }
  const record: PendingSubmission<T> = {
    token,
    scene,
    payload: frozenPayload,
    contextKey,
    createdAt: now,
    ownerUserId,
  };
  latest.data[key] = latestRecords.concat(record as PendingSubmission);
  if (!writeStore(latest.data)) {
    return { ok: false, errorType: 'storage', message: '本地提交记录不可用，请稍后重试' };
  }
  return { ok: true, data: cloneRecord(record) };
}

/** 成功或明确过期后清理当前账号对应的待确认记录。 */
export function clearPendingSubmission(record: Pick<PendingSubmission, 'scene' | 'contextKey' | 'token'>): boolean {
  const key = accountKey();
  if (!key) {
    return false;
  }
  const ownerUserId = Number((record as Partial<PendingSubmission>).ownerUserId);
  if (Number.isFinite(ownerUserId) && ownerUserId !== Number(key)) {
    return false;
  }
  const stored = readStore();
  if (!stored.ok) {
    return false;
  }
  const current = recordsFor(stored.data, key);
  const next = current.filter(
    (candidate) =>
      candidate.scene !== record.scene ||
      candidate.contextKey !== record.contextKey ||
      candidate.token !== record.token,
  );
  if (next.length === current.length) {
    return true;
  }
  stored.data[key] = next;
  return writeStore(stored.data);
}
