/** 列表切换 Tab 时的加载态：有旧数据则保留列表并弱化，无数据才全屏骨架 */

export type FeedSwitchFlags = {
  switching: boolean;
  loading: boolean;
};

/** 筛选 / 分区 Tab 切换：永不整页骨架，仅顶栏细条 + 弱化当前内容（含空状态） */
export function beginFeedTabSwitch(): FeedSwitchFlags {
  return { switching: true, loading: false };
}


export function feedSwitchDone(): FeedSwitchFlags {
  return { switching: false, loading: false };
}

/** 非刷新、非切换时的首屏 loading（list 为空才 true） */
export function feedInitialLoading(
  listLength: number,
  wasRefreshing: boolean,
  wasSwitching: boolean,
): boolean {
  if (wasRefreshing || wasSwitching) {
    return false;
  }
  return listLength === 0;
}

/** 切换失败时：有旧列表则 toast，否则走全屏错误 */
export function feedSwitchFailUsesToast(wasRefreshing: boolean, wasSwitching: boolean): boolean {
  return wasRefreshing || wasSwitching;
}
