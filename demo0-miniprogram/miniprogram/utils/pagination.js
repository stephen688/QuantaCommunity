"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.createInitialFeedState = createInitialFeedState;
exports.resetFeedState = resetFeedState;
exports.mergeFeedList = mergeFeedList;
exports.resolveFinished = resolveFinished;
function createInitialFeedState(filter) {
    return {
        list: [],
        loading: false,
        refreshing: false,
        loadingMore: false,
        finished: false,
        filter,
        cursor: undefined,
        offset: 0,
    };
}
function resetFeedState(_prev, filter) {
    return createInitialFeedState(filter);
}
function mergeFeedList(prevList, nextList) {
    const seen = new Set(prevList.map((i) => i.contentId));
    const out = [...prevList];
    for (const item of nextList) {
        if (!seen.has(item.contentId)) {
            seen.add(item.contentId);
            out.push(item);
        }
    }
    return out;
}
/**
 * 根据接口返回的 hasMore 与当前页条数判断是否已无更多数据。
 */
function resolveFinished(list, pageSize, hasMore) {
    const len = list.length;
    if (hasMore === false) {
        return true;
    }
    if (hasMore === true) {
        return false;
    }
    return len < pageSize;
}
