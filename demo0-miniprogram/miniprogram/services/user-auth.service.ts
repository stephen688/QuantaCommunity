import { USE_MOCK } from '../constants/request';
import { mockDelay } from '../mock/delay';
import type { UserAuthDTO, UserAuthStatusVO, UserAuthVO } from '../types/auth';
import { bumpAppRefresh } from '../utils/app-refresh-bus';
import { request, type RequestResult } from '../utils/request';

/** Mock：内存认证状态，便于离线验收流程 */
let mockAuthAuditStatus = -1;
let mockAuthRemark = '';
let mockAuthDetail: UserAuthVO | null = null;

function mockStatusPayload(): UserAuthStatusVO {
  return {
    auditStatus: mockAuthAuditStatus,
    auditRemark: mockAuthRemark || undefined,
  };
}

export async function getAuthStatus(): Promise<RequestResult<UserAuthStatusVO>> {
  if (USE_MOCK) {
    await mockDelay();
    return { ok: true, data: mockStatusPayload() };
  }
  const res = await request<UserAuthStatusVO>({
    method: 'GET',
    url: '/user/auth/status',
  });
  if (!res.ok) {
    return res;
  }
  const d = res.data;
  if (d === undefined || d === null || typeof d !== 'object') {
    return { ok: false, errorType: 'invalidData', message: '认证状态数据异常' };
  }
  return { ok: true, data: d };
}

/**
 * 后端仅在「已通过」时返回详情；未通过/未提交会报错，调用方需捕获或先判断状态。
 */
export async function getAuthDetail(): Promise<RequestResult<UserAuthVO>> {
  if (USE_MOCK) {
    await mockDelay();
    if (mockAuthAuditStatus !== 1 || !mockAuthDetail) {
      return { ok: false, errorType: 'server', message: '用户认证信息未通过' };
    }
    return { ok: true, data: { ...mockAuthDetail } };
  }
  const res = await request<UserAuthVO>({
    method: 'GET',
    url: '/user/auth/detail',
  });
  if (!res.ok) {
    return res;
  }
  const d = res.data;
  if (d === undefined || d === null || typeof d !== 'object') {
    return { ok: false, errorType: 'invalidData', message: '认证详情数据异常' };
  }
  return { ok: true, data: d };
}

export async function submitUserAuth(payload: UserAuthDTO): Promise<RequestResult<UserAuthVO>> {
  if (USE_MOCK) {
    await mockDelay();
    if (mockAuthAuditStatus === 0) {
      return { ok: false, errorType: 'server', message: '用户正在审核中，不能重新认证' };
    }
    if (mockAuthAuditStatus === 1) {
      return { ok: false, errorType: 'server', message: '用户已经认证过了' };
    }
    // -1 未提交 或 2 已驳回：允许提交，进入待审核
    mockAuthAuditStatus = 0;
    mockAuthRemark = '';
    mockAuthDetail = {
      identityType: payload.identityType,
      realName: payload.realName,
      schoolId: payload.schoolId,
      quantaBatch: payload.quantaBatch,
      quantaDepartment: payload.quantaDepartment,
      auditStatus: 0,
    };
    bumpAppRefresh('userAuth');
    return { ok: true, data: { ...mockAuthDetail } };
  }
  const res = await request<UserAuthVO>({
    method: 'POST',
    url: '/user/auth/add',
    data: { ...payload },
  });
  if (res.ok) {
    bumpAppRefresh('userAuth');
  }
  return res;
}
