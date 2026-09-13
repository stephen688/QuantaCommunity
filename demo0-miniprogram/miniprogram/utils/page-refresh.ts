import {
  consumeAppRefresh,
  snapshotPageRefresh,
  shouldRefreshSince,
  type AppRefreshScope,
  type PageRefreshSnapshot,
} from './app-refresh-bus';

export type { PageRefreshSnapshot };

export function createPageRefreshSnapshot(): PageRefreshSnapshot {
  return snapshotPageRefresh();
}

export function shouldReloadOnShow(
  snapshot: PageRefreshSnapshot,
  scopes: AppRefreshScope[],
): boolean {
  return scopes.some((scope) => shouldRefreshSince(scope, snapshot[scope]));
}

/** 将 snapshot 对齐到当前 revision（在触发 reload 后调用） */
export function markRefreshConsumed(
  snapshot: PageRefreshSnapshot,
  scopes: AppRefreshScope[],
): void {
  consumeAppRefresh(snapshot, scopes);
}

/** 栈内列表页：资料或「我的内容」变更后 onShow 重载首屏 */
export function handleMineListPageOnShow(
  page: { _pageHasShown?: boolean; _refreshSnapshot: PageRefreshSnapshot },
  reload: () => void,
): void {
  if (!page._pageHasShown) {
    page._pageHasShown = true;
    return;
  }
  if (shouldReloadOnShow(page._refreshSnapshot, ['userProfile', 'myContent'])) {
    markRefreshConsumed(page._refreshSnapshot, ['userProfile', 'myContent']);
    reload();
  }
}

/**
 * 详情页：从编辑资料返回后刷新作者信息与自己的评论昵称。
 * @returns 是否已触发 reload（调用方可在 true 时跳过后续 onShow 逻辑）
 */
export function handleDetailProfileRefreshOnShow(
  page: { _pageHasShown?: boolean; _refreshSnapshot: PageRefreshSnapshot },
  reload: () => void | Promise<void>,
): boolean {
  if (!page._pageHasShown) {
    page._pageHasShown = true;
    return false;
  }
  if (!shouldReloadOnShow(page._refreshSnapshot, ['userProfile'])) {
    return false;
  }
  markRefreshConsumed(page._refreshSnapshot, ['userProfile']);
  void reload();
  return true;
}
