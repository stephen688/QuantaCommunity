"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.resolveContentCardFromEvent = resolveContentCardFromEvent;
exports.navigateFromContentCardEvent = navigateFromContentCardEvent;
const content_1 = require("../constants/content");
const route_1 = require("../constants/route");
/**
 * 从 content-card 的 cardtap 事件解析帖子 ID / 类型。
 * 优先 detail.item；部分基础库下自定义事件 tap 的 detail 会丢失，回退 currentTarget.dataset。
 */
function resolveContentCardFromEvent(e) {
    const detailItem = e.detail?.item;
    const fromDetail = parseCardNavTarget(detailItem?.contentId, detailItem?.contentType);
    if (fromDetail) {
        return fromDetail;
    }
    const ds = e.currentTarget?.dataset;
    return parseCardNavTarget(ds?.contentId, ds?.contentType);
}
function parseCardNavTarget(rawId, rawType) {
    if (rawId === undefined || rawId === null || rawId === '') {
        return null;
    }
    const contentId = Number(rawId);
    if (!Number.isFinite(contentId)) {
        return null;
    }
    const contentType = Number(rawType);
    if (contentType !== content_1.CONTENT_TYPE_LIFE && contentType !== content_1.CONTENT_TYPE_PROFESSIONAL) {
        return null;
    }
    return { contentId, contentType };
}
/** 跳转帖子详情；无法解析时提示并返回 false */
function navigateFromContentCardEvent(e) {
    const card = resolveContentCardFromEvent(e);
    if (!card) {
        wx.showToast({ title: '内容无效', icon: 'none' });
        return false;
    }
    if (card.contentType === content_1.CONTENT_TYPE_LIFE) {
        wx.navigateTo({ url: `${route_1.ROUTES.DETAIL_LIFE}?contentId=${card.contentId}` });
        return true;
    }
    wx.navigateTo({ url: `${route_1.ROUTES.DETAIL_PRO}?contentId=${card.contentId}` });
    return true;
}
