"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.getAuthStatus = getAuthStatus;
exports.getAuthDetail = getAuthDetail;
exports.submitUserAuth = submitUserAuth;
const request_1 = require("../constants/request");
const delay_1 = require("../mock/delay");
const app_refresh_bus_1 = require("../utils/app-refresh-bus");
const request_2 = require("../utils/request");
/** Mock：内存认证状态，便于离线验收流程 */
let mockAuthAuditStatus = -1;
let mockAuthRemark = '';
let mockAuthDetail = null;
function mockStatusPayload() {
    return {
        auditStatus: mockAuthAuditStatus,
        auditRemark: mockAuthRemark || undefined,
    };
}
async function getAuthStatus() {
    if (request_1.USE_MOCK) {
        await (0, delay_1.mockDelay)();
        return { ok: true, data: mockStatusPayload() };
    }
    const res = await (0, request_2.request)({
        method: 'GET',
        url: '/user/auth/status',
    });
    if (!res.ok) {
        return res;
    }
    const d = res.data;
    if (d === undefined || d === null || typeof d !== 'object') {
        return { ok: false, errorType: 'invalidData', message: '认证状态数据异常' };
    }
    return { ok: true, data: d };
}
/**
 * 后端仅在「已通过」时返回详情；未通过/未提交会报错，调用方需捕获或先判断状态。
 */
async function getAuthDetail() {
    if (request_1.USE_MOCK) {
        await (0, delay_1.mockDelay)();
        if (mockAuthAuditStatus !== 1 || !mockAuthDetail) {
            return { ok: false, errorType: 'server', message: '用户认证信息未通过' };
        }
        return { ok: true, data: { ...mockAuthDetail } };
    }
    const res = await (0, request_2.request)({
        method: 'GET',
        url: '/user/auth/detail',
    });
    if (!res.ok) {
        return res;
    }
    const d = res.data;
    if (d === undefined || d === null || typeof d !== 'object') {
        return { ok: false, errorType: 'invalidData', message: '认证详情数据异常' };
    }
    return { ok: true, data: d };
}
async function submitUserAuth(payload) {
    if (request_1.USE_MOCK) {
        await (0, delay_1.mockDelay)();
        if (mockAuthAuditStatus === 0) {
            return { ok: false, errorType: 'server', message: '用户正在审核中，不能重新认证' };
        }
        if (mockAuthAuditStatus === 1) {
            return { ok: false, errorType: 'server', message: '用户已经认证过了' };
        }
        // -1 未提交 或 2 已驳回：允许提交，进入待审核
        mockAuthAuditStatus = 0;
        mockAuthRemark = '';
        mockAuthDetail = {
            identityType: payload.identityType,
            realName: payload.realName,
            schoolId: payload.schoolId,
            quantaBatch: payload.quantaBatch,
            quantaDepartment: payload.quantaDepartment,
            auditStatus: 0,
        };
        (0, app_refresh_bus_1.bumpAppRefresh)('userAuth');
        return { ok: true, data: { ...mockAuthDetail } };
    }
    const res = await (0, request_2.request)({
        method: 'POST',
        url: '/user/auth/add',
        data: { ...payload },
    });
    if (res.ok) {
        (0, app_refresh_bus_1.bumpAppRefresh)('userAuth');
    }
    return res;
}
