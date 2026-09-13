"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.loginWithCode = loginWithCode;
exports.isLoggedIn = isLoggedIn;
exports.loginWithWeChat = loginWithWeChat;
exports.promptLoginIfNeeded = promptLoginIfNeeded;
exports.ensureLoggedIn = ensureLoggedIn;
exports.logout = logout;
const request_1 = require("../constants/request");
const login_sheet_bus_1 = require("../utils/login-sheet-bus");
const auth_nav_1 = require("../utils/auth-nav");
const app_refresh_bus_1 = require("../utils/app-refresh-bus");
const request_2 = require("../utils/request");
const storage_1 = require("../utils/storage");
const TOURIST_APP_ID = 'touristappid';
function shouldUseDevLoginCode() {
    try {
        const account = wx.getAccountInfoSync();
        return account.miniProgram.appId === TOURIST_APP_ID;
    }
    catch {
        return true;
    }
}
function wxLoginCode() {
    return new Promise((resolve, reject) => {
        wx.login({
            timeout: 10000,
            success(res) {
                if (res.code) {
                    resolve(res.code);
                    return;
                }
                reject(new Error('wx.login 未返回 code'));
            },
            fail(err) {
                reject(err ?? new Error('wx.login 失败'));
            },
        });
    });
}
function isMockWxLoginCode(code) {
    return /mock/i.test(code);
}
async function resolveLoginCode() {
    if (shouldUseDevLoginCode()) {
        return request_1.DEV_LOGIN_CODE;
    }
    const code = await wxLoginCode();
    // 开发者工具模拟器即使用真实 AppID，wx.login 也会返回 mock code，无法调微信 jscode2session
    if (isMockWxLoginCode(code)) {
        return request_1.DEV_LOGIN_CODE;
    }
    return code;
}
function cacheLoginUser(login) {
    if (!login || typeof login !== 'object') {
        return;
    }
    const userId = login.id === undefined || login.id === null ? undefined : Number(login.id);
    if (Number.isFinite(userId) && Number(userId) > 0) {
        (0, storage_1.setLoginUserId)(Number(userId));
    }
    (0, app_refresh_bus_1.setCachedUserInfo)({
        userId: Number.isFinite(userId) ? userId : undefined,
        nickName: login.nickName,
        avatarUrl: login.avatarUrl,
    });
    (0, app_refresh_bus_1.bumpAppRefresh)('userProfile');
}
async function loginWithCode(code, profile) {
    const data = { code };
    const nickName = profile?.nickName?.trim();
    const avatarUrl = profile?.avatarUrl?.trim();
    if (nickName) {
        data.nickName = nickName;
    }
    if (avatarUrl) {
        data.avatarUrl = avatarUrl;
    }
    return (0, request_2.request)({
        method: 'POST',
        url: '/user/login',
        data,
    });
}
function isLoggedIn() {
    return Boolean((0, storage_1.getToken)());
}
/** 微信一键登录：wx.login（或联调 mock code）换 JWT；可附带授权后的昵称/头像 */
async function loginWithWeChat(profile) {
    try {
        const code = await resolveLoginCode();
        const res = await loginWithCode(code, profile);
        if (res.ok && res.data?.token) {
            (0, storage_1.setToken)(res.data.token);
            cacheLoginUser(res.data);
            (0, login_sheet_bus_1.hideLoginSheet)();
            return true;
        }
        const msg = res.ok ? '登录失败，请重试' : res.message || '登录失败';
        wx.showToast({ title: msg.slice(0, 14), icon: 'none' });
        return false;
    }
    catch {
        wx.showToast({ title: '微信登录失败', icon: 'none' });
        return false;
    }
}
/**
 * 未登录时跳转登录页（不弹出资料授权层）。
 */
function promptLoginIfNeeded() {
    if (isLoggedIn()) {
        return;
    }
    (0, auth_nav_1.navigateToLoginPage)();
}
async function ensureLoggedIn(options) {
    if (isLoggedIn()) {
        return true;
    }
    if (!options?.silent) {
        (0, auth_nav_1.navigateToLoginPage)();
    }
    return false;
}
function logout() {
    (0, storage_1.clearLoginUserId)();
    (0, storage_1.clearToken)();
}
