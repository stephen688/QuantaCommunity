"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.showAuthInterceptModal = showAuthInterceptModal;
exports.hideAuthInterceptModal = hideAuthInterceptModal;
exports.isAuthInterceptVisible = isAuthInterceptVisible;
exports.subscribeAuthInterceptModal = subscribeAuthInterceptModal;
let currentPayload = null;
const listeners = new Set();
function notify() {
    listeners.forEach((fn) => fn(currentPayload));
}
/** 展示实名认证引导弹窗 */
function showAuthInterceptModal(payload) {
    currentPayload = payload ?? {};
    notify();
}
/** 关闭实名认证引导弹窗 */
function hideAuthInterceptModal() {
    if (!currentPayload) {
        return;
    }
    currentPayload = null;
    notify();
}
function isAuthInterceptVisible() {
    return currentPayload !== null;
}
/** 订阅弹窗显隐；注册时立即同步当前状态 */
function subscribeAuthInterceptModal(listener) {
    listeners.add(listener);
    listener(currentPayload);
    return () => {
        listeners.delete(listener);
    };
}
