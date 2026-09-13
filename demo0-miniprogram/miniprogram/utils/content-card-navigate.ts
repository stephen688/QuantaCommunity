import { CONTENT_TYPE_LIFE, CONTENT_TYPE_PROFESSIONAL, type ContentType } from '../constants/content';
import { ROUTES } from '../constants/route';
import type { ContentCardModel } from '../types/content';

type CardNavTarget = Pick<ContentCardModel, 'contentId' | 'contentType'>;

/**
 * 从 content-card 的 cardtap 事件解析帖子 ID / 类型。
 * 优先 detail.item；部分基础库下自定义事件 tap 的 detail 会丢失，回退 currentTarget.dataset。
 */
export function resolveContentCardFromEvent(
  e: WechatMiniprogram.CustomEvent<{ item?: ContentCardModel }>,
): CardNavTarget | null {
  const detailItem = e.detail?.item;
  const fromDetail = parseCardNavTarget(detailItem?.contentId, detailItem?.contentType);
  if (fromDetail) {
    return fromDetail;
  }

  const ds = e.currentTarget?.dataset as
    | { contentId?: number | string; contentType?: number | string }
    | undefined;
  return parseCardNavTarget(ds?.contentId, ds?.contentType);
}

function parseCardNavTarget(
  rawId: unknown,
  rawType: unknown,
): CardNavTarget | null {
  if (rawId === undefined || rawId === null || rawId === '') {
    return null;
  }
  const contentId = Number(rawId);
  if (!Number.isFinite(contentId)) {
    return null;
  }
  const contentType = Number(rawType) as ContentType;
  if (contentType !== CONTENT_TYPE_LIFE && contentType !== CONTENT_TYPE_PROFESSIONAL) {
    return null;
  }
  return { contentId, contentType };
}

/** 跳转帖子详情；无法解析时提示并返回 false */
export function navigateFromContentCardEvent(
  e: WechatMiniprogram.CustomEvent<{ item?: ContentCardModel }>,
): boolean {
  const card = resolveContentCardFromEvent(e);
  if (!card) {
    wx.showToast({ title: '内容无效', icon: 'none' });
    return false;
  }
  if (card.contentType === CONTENT_TYPE_LIFE) {
    wx.navigateTo({ url: `${ROUTES.DETAIL_LIFE}?contentId=${card.contentId}` });
    return true;
  }
  wx.navigateTo({ url: `${ROUTES.DETAIL_PRO}?contentId=${card.contentId}` });
  return true;
}
