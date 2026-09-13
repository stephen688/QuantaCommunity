"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.normalizeImages = normalizeImages;
exports.pickCoverImage = pickCoverImage;
exports.withDefaultAvatar = withDefaultAvatar;
/**
 * 过滤空串；无图时返回空数组（由 UI 决定是否占位）。
 */
function normalizeImages(images) {
    if (!images || !Array.isArray(images)) {
        return [];
    }
    return images.map((s) => (typeof s === 'string' ? s.trim() : '')).filter(Boolean);
}
/** 列表首张图，无则 undefined */
function pickCoverImage(images) {
    const list = normalizeImages(images);
    return list[0];
}
/**
 * 头像兜底：无有效 URL 时返回空串，避免组件 String 属性收到 null。
 */
function withDefaultAvatar(avatarUrl) {
    if (typeof avatarUrl !== 'string') {
        return '';
    }
    return avatarUrl.trim();
}
