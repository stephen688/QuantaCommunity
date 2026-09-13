export type AuthInterceptPayload = {
  title?: string;
  description?: string;
  reason?: string;
};

type AuthInterceptListener = (payload: AuthInterceptPayload | null) => void;

let currentPayload: AuthInterceptPayload | null = null;
const listeners = new Set<AuthInterceptListener>();

function notify(): void {
  listeners.forEach((fn) => fn(currentPayload));
}

/** 展示实名认证引导弹窗 */
export function showAuthInterceptModal(payload?: AuthInterceptPayload): void {
  currentPayload = payload ?? {};
  notify();
}

/** 关闭实名认证引导弹窗 */
export function hideAuthInterceptModal(): void {
  if (!currentPayload) {
    return;
  }
  currentPayload = null;
  notify();
}

export function isAuthInterceptVisible(): boolean {
  return currentPayload !== null;
}

/** 订阅弹窗显隐；注册时立即同步当前状态 */
export function subscribeAuthInterceptModal(listener: AuthInterceptListener): () => void {
  listeners.add(listener);
  listener(currentPayload);
  return () => {
    listeners.delete(listener);
  };
}
