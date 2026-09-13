"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.isRealNameVerified = isRealNameVerified;
exports.ensureRealNameVerified = ensureRealNameVerified;
exports.navigateToRealNameVerify = navigateToRealNameVerify;
const route_1 = require("../constants/route");
const user_auth_service_1 = require("./user-auth.service");
const app_refresh_bus_1 = require("../utils/app-refresh-bus");
const auth_intercept_bus_1 = require("../utils/auth-intercept-bus");
const auth_service_1 = require("./auth.service");
/**
 * 是否已通过实名认证。
 * - GET /user/auth/status：tb_user_auth.audit_status，1=已通过
 * - 主页 profile：tb_user.auth_status，2=已认证（与审核表 1 不是同一套枚举）
 */
async function isRealNameVerified() {
    const res = await (0, user_auth_service_1.getAuthStatus)();
    if (res.ok && res.data?.auditStatus === 1) {
        return true;
    }
    const cached = (0, app_refresh_bus_1.getCachedUserInfo)();
    if (cached?.authStatus === 2) {
        return true;
    }
    return false;
}
/**
 * 确保已完成实名认证。
 * 未登录时先唤起登录层；未认证时弹出引导弹窗。
 */
async function ensureRealNameVerified(options) {
    if (!(0, auth_service_1.isLoggedIn)()) {
        if (!options?.silent) {
            await (0, auth_service_1.ensureLoggedIn)();
        }
        return false;
    }
    const verified = await isRealNameVerified();
    if (verified || options?.silent) {
        return verified;
    }
    (0, auth_intercept_bus_1.showAuthInterceptModal)({
        title: options?.title,
        description: options?.description,
        reason: options?.reason,
    });
    return false;
}
function navigateToRealNameVerify() {
    wx.navigateTo({ url: `${route_1.ROUTES.PROFILE_EDIT}?intent=verify` });
}
