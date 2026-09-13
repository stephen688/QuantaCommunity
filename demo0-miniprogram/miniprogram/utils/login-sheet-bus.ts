type LoginSheetListener = (visible: boolean) => void;

let visible = false;
/** 用户点击「暂不登录」后，本次冷启动内不再自动弹出 */
let skippedThisSession = false;
const listeners = new Set<LoginSheetListener>();

function notify(): void {
  listeners.forEach((fn) => fn(visible));
}

/** 展示底部登录弹层（未登录启动、401 等场景） */
export function showLoginSheet(options?: { force?: boolean }): void {
  const force = Boolean(options?.force);
  if (visible && !force) {
    return;
  }
  visible = true;
  notify();
}

/** 本次会话内不再自动弹出（仍可由 401 等再次唤起） */
export function skipLoginForSession(): void {
  skippedThisSession = true;
  hideLoginSheet();
}

export function shouldAutoShowLoginSheet(): boolean {
  return !skippedThisSession;
}

export function resetLoginSheetSession(): void {
  skippedThisSession = false;
}

/** 关闭底部登录弹层 */
export function hideLoginSheet(): void {
  if (!visible) {
    return;
  }
  visible = false;
  notify();
}

export function isLoginSheetVisible(): boolean {
  return visible;
}

/** 订阅弹层显隐；注册时立即同步当前状态 */
export function subscribeLoginSheet(listener: LoginSheetListener): () => void {
  listeners.add(listener);
  listener(visible);
  return () => {
    listeners.delete(listener);
  };
}
