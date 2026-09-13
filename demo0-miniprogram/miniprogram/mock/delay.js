"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.mockDelay = mockDelay;
const request_1 = require("../constants/request");
/** 模拟网络耗时，便于观察骨架屏与加载态 */
function mockDelay(ms = request_1.MOCK_DELAY_MS) {
    return new Promise((resolve) => {
        setTimeout(resolve, ms);
    });
}
