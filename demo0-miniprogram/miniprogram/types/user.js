"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.userProfileAuthStatusText = userProfileAuthStatusText;
const AUTH_STATUS_LABELS = {
    0: '未认证',
    1: '审核中',
    2: '已认证',
    3: '审核不通过',
};
/**
 * 公开主页 `authStatus` 展示文案（与后端 UserProfileVO 约定一致）。
 */
function userProfileAuthStatusText(authStatus) {
    const key = authStatus ?? 0;
    return AUTH_STATUS_LABELS[key] ?? '未认证';
}
