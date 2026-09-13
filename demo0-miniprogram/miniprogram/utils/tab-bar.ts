import { getUnreadCount } from '../services/notification.service';

export type CustomTabBar = WechatMiniprogram.Component.TrivialInstance & {
  setSelected?: (index: number) => void;
  setUnread?: (count?: number) => void;
};

export function getCustomTabBar(
  page: WechatMiniprogram.Page.TrivialInstance,
): CustomTabBar | undefined {
  return page.getTabBar?.() as CustomTabBar | undefined;
}

export function syncCustomTabBarSelected(
  page: WechatMiniprogram.Page.TrivialInstance,
  index: number,
): void {
  getCustomTabBar(page)?.setSelected?.(index);
}

export async function refreshCustomTabBarUnread(
  page: WechatMiniprogram.Page.TrivialInstance,
): Promise<void> {
  const res = await getUnreadCount();
  const count = res.ok ? res.data : 0;
  getCustomTabBar(page)?.setUnread?.(count);
}
