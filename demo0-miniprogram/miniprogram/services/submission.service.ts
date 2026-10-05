import { request, type RequestResult } from '../utils/request';
import type { SubmissionScene } from '../utils/submission';

export type SubmissionStatus = 'SUCCEEDED' | 'UNCONFIRMED' | 'EXPIRED';

export interface SubmissionStatusVO<T = unknown> {
  status: SubmissionStatus;
  scene: SubmissionScene;
  data?: T;
  expiresAt?: string;
}

/** 查询当前登录用户自己的提交凭证状态；只读，不触发业务重试或写入。 */
export function querySubmissionStatus<T = unknown>(
  scene: SubmissionScene,
  submissionToken: string,
): Promise<RequestResult<SubmissionStatusVO<T>>> {
  return request<SubmissionStatusVO<T>>({
    method: 'GET',
    url: `/submission/status?scene=${encodeURIComponent(scene)}`,
    header: { 'Idempotency-Key': submissionToken },
  });
}
