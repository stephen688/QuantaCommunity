"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.markFollowFeedStale = markFollowFeedStale;
exports.consumeFollowFeedStale = consumeFollowFeedStale;
/** 关注流需在下次进入「关注」Tab 时刷新 */
function markFollowFeedStale() {
    const app = getApp();
    if (app) {
        app.globalData.followFeedStale = true;
    }
}
function consumeFollowFeedStale() {
    const app = getApp();
    if (app?.globalData.followFeedStale) {
        app.globalData.followFeedStale = false;
        return true;
    }
    return false;
}
