import type { ContentCardModel } from '../types/content';

/** 卡片内点赞/收藏后，同步页面列表中的对应项 */
export function patchContentCardInList(
  list: ContentCardModel[],
  next: ContentCardModel | undefined | null,
): ContentCardModel[] {
  if (!next?.contentId) {
    return list;
  }
  return list.map((row) => (row.contentId === next.contentId ? { ...row, ...next } : row));
}
