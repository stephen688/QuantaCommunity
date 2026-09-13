"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.sliceMockScrollResult = sliceMockScrollResult;
/**
 * 按 offset 在本地池中切片，形状对齐 ScrollResultVO，供推荐流 / 关注流 mock。
 */
function sliceMockScrollResult(pool, offset, pageSize) {
    const start = Math.max(0, Math.floor(offset));
    const list = pool.slice(start, start + pageSize);
    const nextOffset = start + list.length;
    const last = list[list.length - 1];
    const minScore = last !== undefined && typeof last.contentId === 'number'
        ? last.contentId
        : undefined;
    return {
        list,
        minScore,
        offset: nextOffset,
        hasMore: nextOffset < pool.length,
    };
}
