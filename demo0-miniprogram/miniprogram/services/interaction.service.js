"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.setContentLike = setContentLike;
exports.setContentCollect = setContentCollect;
const request_1 = require("../constants/request");
const delay_1 = require("../mock/delay");
const detail_interaction_1 = require("../mock/detail-interaction");
const request_2 = require("../utils/request");
function setContentLike(contentId, liked) {
    if (!Number.isFinite(contentId) || contentId <= 0) {
        return Promise.resolve({
            ok: false,
            errorType: 'invalidData',
            message: '内容不存在或参数无效',
        });
    }
    if (typeof liked !== 'boolean') {
        return Promise.resolve({
            ok: false,
            errorType: 'invalidData',
            message: '点赞状态无效',
        });
    }
    if (request_1.USE_MOCK) {
        return (async () => {
            await (0, delay_1.mockDelay)();
            return { ok: true, data: (0, detail_interaction_1.mockSetContentLike)(contentId, liked) };
        })();
    }
    return (0, request_2.request)({
        method: 'POST',
        url: `/content/like/${contentId}`,
        data: { liked },
    });
}
function setContentCollect(contentId, collected) {
    if (!Number.isFinite(contentId) || contentId <= 0) {
        return Promise.resolve({
            ok: false,
            errorType: 'invalidData',
            message: '内容不存在或参数无效',
        });
    }
    if (typeof collected !== 'boolean') {
        return Promise.resolve({
            ok: false,
            errorType: 'invalidData',
            message: '收藏状态无效',
        });
    }
    if (request_1.USE_MOCK) {
        return (async () => {
            await (0, delay_1.mockDelay)();
            return { ok: true, data: (0, detail_interaction_1.mockSetContentCollect)(contentId, collected) };
        })();
    }
    return (0, request_2.request)({
        method: 'POST',
        url: `/content/collect/${contentId}`,
        data: { collected },
    });
}
