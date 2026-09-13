"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.mockPostImages = mockPostImages;
/**
 * Mock 配图 URL（与后端「部分帖子有图、部分无图」一致；列表支持最多 5 张）。
 * 真机/正式版需在小程序后台配置 downloadFile 合法域名（如 picsum.photos），或改为你们 CDN/OSS 域名。
 */
function mockPostImages(contentId, count = 1) {
    const n = Math.max(1, Math.min(5, count));
    const out = [];
    for (let i = 0; i < n; i += 1) {
        out.push(`https://picsum.photos/seed/demo0-${contentId}-${i}/800/520`);
    }
    return out;
}
