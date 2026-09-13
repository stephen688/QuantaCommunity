"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.patchContentCardInList = patchContentCardInList;
/** 卡片内点赞/收藏后，同步页面列表中的对应项 */
function patchContentCardInList(list, next) {
    if (!next?.contentId) {
        return list;
    }
    return list.map((row) => (row.contentId === next.contentId ? { ...row, ...next } : row));
}
