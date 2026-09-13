"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.syncMotionReducedPreference = syncMotionReducedPreference;
exports.setMotionReducedPreference = setMotionReducedPreference;
const STORAGE_KEY = 'motion_reduced_pref';
/** 读取用户/调试存储的低动效偏好；系统级偏好由 app.wxss 中 media query 覆盖 */
function syncMotionReducedPreference() {
    try {
        return wx.getStorageSync(STORAGE_KEY) === '1';
    }
    catch {
        return false;
    }
}
function setMotionReducedPreference(reduced) {
    try {
        wx.setStorageSync(STORAGE_KEY, reduced ? '1' : '0');
    }
    catch {
        /* ignore */
    }
}
