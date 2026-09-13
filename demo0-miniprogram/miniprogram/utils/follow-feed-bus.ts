/** 关注流需在下次进入「关注」Tab 时刷新 */
export function markFollowFeedStale(): void {
  const app = getApp<IAppOption>();
  if (app) {
    app.globalData.followFeedStale = true;
  }
}

export function consumeFollowFeedStale(): boolean {
  const app = getApp<IAppOption>();
  if (app?.globalData.followFeedStale) {
    app.globalData.followFeedStale = false;
    return true;
  }
  return false;
}

interface IAppOption {
  globalData: {
    followFeedStale?: boolean;
    motionReduced?: boolean;
  };
}
