import type { CollectResultVO, LikeResultVO } from '../types/interaction';
import { USE_MOCK } from '../constants/request';
import { mockDelay } from '../mock/delay';
import { mockSetContentLike, mockSetContentCollect } from '../mock/detail-interaction';
import { request, type RequestResult } from '../utils/request';

export function setContentLike(contentId: number, liked: boolean): Promise<RequestResult<LikeResultVO>> {
  if (!Number.isFinite(contentId) || contentId <= 0) {
    return Promise.resolve({
      ok: false,
      errorType: 'invalidData',
      message: '内容不存在或参数无效',
    });
  }
  if (typeof liked !== 'boolean') {
    return Promise.resolve({
      ok: false,
      errorType: 'invalidData',
      message: '点赞状态无效',
    });
  }
  if (USE_MOCK) {
    return (async () => {
      await mockDelay();
      return { ok: true, data: mockSetContentLike(contentId, liked) };
    })();
  }
  return request<LikeResultVO>({
    method: 'POST',
    url: `/content/like/${contentId}`,
    data: { liked },
  });
}

export function setContentCollect(contentId: number, collected: boolean): Promise<RequestResult<CollectResultVO>> {
  if (!Number.isFinite(contentId) || contentId <= 0) {
    return Promise.resolve({
      ok: false,
      errorType: 'invalidData',
      message: '内容不存在或参数无效',
    });
  }
  if (typeof collected !== 'boolean') {
    return Promise.resolve({
      ok: false,
      errorType: 'invalidData',
      message: '收藏状态无效',
    });
  }
  if (USE_MOCK) {
    return (async () => {
      await mockDelay();
      return { ok: true, data: mockSetContentCollect(contentId, collected) };
    })();
  }
  return request<CollectResultVO>({
    method: 'POST',
    url: `/content/collect/${contentId}`,
    data: { collected },
  });
}
