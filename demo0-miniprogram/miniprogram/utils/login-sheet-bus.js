"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.showLoginSheet = showLoginSheet;
exports.skipLoginForSession = skipLoginForSession;
exports.shouldAutoShowLoginSheet = shouldAutoShowLoginSheet;
exports.resetLoginSheetSession = resetLoginSheetSession;
exports.hideLoginSheet = hideLoginSheet;
exports.isLoginSheetVisible = isLoginSheetVisible;
exports.subscribeLoginSheet = subscribeLoginSheet;
let visible = false;
/** 用户点击「暂不登录」后，本次冷启动内不再自动弹出 */
let skippedThisSession = false;
const listeners = new Set();
function notify() {
    listeners.forEach((fn) => fn(visible));
}
/** 展示底部登录弹层（未登录启动、401 等场景） */
function showLoginSheet(options) {
    const force = Boolean(options?.force);
    if (visible && !force) {
        return;
    }
    visible = true;
    notify();
}
/** 本次会话内不再自动弹出（仍可由 401 等再次唤起） */
function skipLoginForSession() {
    skippedThisSession = true;
    hideLoginSheet();
}
function shouldAutoShowLoginSheet() {
    return !skippedThisSession;
}
function resetLoginSheetSession() {
    skippedThisSession = false;
}
/** 关闭底部登录弹层 */
function hideLoginSheet() {
    if (!visible) {
        return;
    }
    visible = false;
    notify();
}
function isLoginSheetVisible() {
    return visible;
}
/** 订阅弹层显隐；注册时立即同步当前状态 */
function subscribeLoginSheet(listener) {
    listeners.add(listener);
    listener(visible);
    return () => {
        listeners.delete(listener);
    };
}
