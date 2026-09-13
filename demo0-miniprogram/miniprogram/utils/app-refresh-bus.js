"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.getAppRefreshRevision = getAppRefreshRevision;
exports.bumpAppRefresh = bumpAppRefresh;
exports.shouldRefreshSince = shouldRefreshSince;
exports.getCachedUserInfo = getCachedUserInfo;
exports.setCachedUserInfo = setCachedUserInfo;
exports.mergeCachedUserInfo = mergeCachedUserInfo;
exports.snapshotPageRefresh = snapshotPageRefresh;
exports.consumeAppRefresh = consumeAppRefresh;
const revisions = {
    userProfile: 0,
    userAuth: 0,
    myContent: 0,
};
let cachedUserInfo = null;
function getAppRefreshRevision(scope) {
    return revisions[scope];
}
function bumpAppRefresh(...scopes) {
    for (const s of scopes) {
        revisions[s] += 1;
    }
}
function shouldRefreshSince(scope, seenRevision) {
    return revisions[scope] > seenRevision;
}
function getCachedUserInfo() {
    return cachedUserInfo;
}
function setCachedUserInfo(info) {
    cachedUserInfo = info ? { ...info } : null;
}
/** 合并最新资料到内存缓存（不增加 revision） */
function mergeCachedUserInfo(patch) {
    if (!patch || typeof patch !== 'object') {
        return;
    }
    cachedUserInfo = { ...(cachedUserInfo ?? {}), ...patch };
}
function snapshotPageRefresh() {
    return {
        userProfile: revisions.userProfile,
        userAuth: revisions.userAuth,
        myContent: revisions.myContent,
    };
}
/**
 * 若指定 scope 有更新，将 snapshot 推进到当前 revision 并返回 true。
 */
function consumeAppRefresh(snapshot, scopes) {
    let dirty = false;
    for (const scope of scopes) {
        if (shouldRefreshSince(scope, snapshot[scope])) {
            dirty = true;
            snapshot[scope] = revisions[scope];
        }
    }
    return dirty;
}
