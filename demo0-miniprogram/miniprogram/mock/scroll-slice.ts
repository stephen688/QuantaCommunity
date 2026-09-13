import type { ContentVO } from '../types/content';
import type { ScrollResultVO } from '../types/api';

/**
 * 按 offset 在本地池中切片，形状对齐 ScrollResultVO，供推荐流 / 关注流 mock。
 */
export function sliceMockScrollResult(
  pool: ContentVO[],
  offset: number,
  pageSize: number,
): ScrollResultVO<ContentVO> {
  const start = Math.max(0, Math.floor(offset));
  const list = pool.slice(start, start + pageSize);
  const nextOffset = start + list.length;
  const last = list[list.length - 1];
  const minScore =
    last !== undefined && typeof last.contentId === 'number'
      ? last.contentId
      : undefined;
  return {
    list,
    minScore,
    offset: nextOffset,
    hasMore: nextOffset < pool.length,
  };
}
