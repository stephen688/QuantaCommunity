"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.navigateToLoginPage = navigateToLoginPage;
const login_sheet_bus_1 = require("./login-sheet-bus");
const LOGIN_ROUTE = '/pages/login/index';
let navigating = false;
/** 跳转登录页（不弹出资料授权层，需用户在登录页点击「微信登录」） */
function navigateToLoginPage() {
    if (navigating) {
        return;
    }
    const pages = getCurrentPages();
    const current = pages[pages.length - 1];
    if (current?.route === 'pages/login/index') {
        return;
    }
    navigating = true;
    (0, login_sheet_bus_1.hideLoginSheet)();
    wx.navigateTo({
        url: LOGIN_ROUTE,
        complete: () => {
            navigating = false;
        },
    });
}
