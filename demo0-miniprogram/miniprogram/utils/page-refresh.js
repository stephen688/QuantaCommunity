"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.createPageRefreshSnapshot = createPageRefreshSnapshot;
exports.shouldReloadOnShow = shouldReloadOnShow;
exports.markRefreshConsumed = markRefreshConsumed;
exports.handleMineListPageOnShow = handleMineListPageOnShow;
exports.handleDetailProfileRefreshOnShow = handleDetailProfileRefreshOnShow;
const app_refresh_bus_1 = require("./app-refresh-bus");
function createPageRefreshSnapshot() {
    return (0, app_refresh_bus_1.snapshotPageRefresh)();
}
function shouldReloadOnShow(snapshot, scopes) {
    return scopes.some((scope) => (0, app_refresh_bus_1.shouldRefreshSince)(scope, snapshot[scope]));
}
/** 将 snapshot 对齐到当前 revision（在触发 reload 后调用） */
function markRefreshConsumed(snapshot, scopes) {
    (0, app_refresh_bus_1.consumeAppRefresh)(snapshot, scopes);
}
/** 栈内列表页：资料或「我的内容」变更后 onShow 重载首屏 */
function handleMineListPageOnShow(page, reload) {
    if (!page._pageHasShown) {
        page._pageHasShown = true;
        return;
    }
    if (shouldReloadOnShow(page._refreshSnapshot, ['userProfile', 'myContent'])) {
        markRefreshConsumed(page._refreshSnapshot, ['userProfile', 'myContent']);
        reload();
    }
}
/**
 * 详情页：从编辑资料返回后刷新作者信息与自己的评论昵称。
 * @returns 是否已触发 reload（调用方可在 true 时跳过后续 onShow 逻辑）
 */
function handleDetailProfileRefreshOnShow(page, reload) {
    if (!page._pageHasShown) {
        page._pageHasShown = true;
        return false;
    }
    if (!shouldReloadOnShow(page._refreshSnapshot, ['userProfile'])) {
        return false;
    }
    markRefreshConsumed(page._refreshSnapshot, ['userProfile']);
    void reload();
    return true;
}
