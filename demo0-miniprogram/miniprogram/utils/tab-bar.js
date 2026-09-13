"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.getCustomTabBar = getCustomTabBar;
exports.syncCustomTabBarSelected = syncCustomTabBarSelected;
exports.refreshCustomTabBarUnread = refreshCustomTabBarUnread;
const notification_service_1 = require("../services/notification.service");
function getCustomTabBar(page) {
    return page.getTabBar?.();
}
function syncCustomTabBarSelected(page, index) {
    getCustomTabBar(page)?.setSelected?.(index);
}
async function refreshCustomTabBarUnread(page) {
    const res = await (0, notification_service_1.getUnreadCount)();
    const count = res.ok ? res.data : 0;
    getCustomTabBar(page)?.setUnread?.(count);
}
