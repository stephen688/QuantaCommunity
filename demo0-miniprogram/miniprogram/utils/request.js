"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.resolveBaseUrl = resolveBaseUrl;
exports.request = request;
const request_1 = require("../constants/request");
const auth_nav_1 = require("./auth-nav");
const storage_1 = require("./storage");
/** 开发者工具默认本机 9191；DEVTOOLS_USE_REMOTE=true 时与真机同走 ngrok */
function resolveBaseUrl() {
    try {
        const sys = wx.getSystemInfoSync();
        if (sys.platform === 'devtools' && !request_1.DEVTOOLS_USE_REMOTE) {
            return request_1.LOCAL_BASE_URL;
        }
    }
    catch {
        /* 非小程序环境 */
    }
    return request_1.REMOTE_BASE_URL;
}
function joinUrl(path) {
    if (/^https?:\/\//i.test(path)) {
        return path;
    }
    const base = resolveBaseUrl().replace(/\/$/, '');
    const p = path.startsWith('/') ? path : `/${path}`;
    return `${base}${p}`;
}
function showToastIfNeeded(showErrorToast, message) {
    if (showErrorToast) {
        wx.showToast({ title: message.slice(0, 14) || '请求失败', icon: 'none' });
    }
}
function request(options) {
    const { method = 'GET', url, data, header = {}, showErrorToast = false } = options;
    const token = (0, storage_1.getToken)();
    const mergedHeader = {
        'ngrok-skip-browser-warning': '1',
        ...header,
    };
    if (token) {
        mergedHeader[request_1.TOKEN_KEY] = token;
    }
    return new Promise((resolve) => {
        wx.request({
            url: joinUrl(url),
            method,
            data,
            header: mergedHeader,
            timeout: request_1.REQUEST_TIMEOUT,
            success(res) {
                const status = res.statusCode;
                if (status === 401) {
                    (0, storage_1.clearToken)();
                    (0, auth_nav_1.navigateToLoginPage)();
                    const msg = '登录已失效，请重新登录';
                    showToastIfNeeded(showErrorToast, msg);
                    resolve({ ok: false, errorType: 'unauthorized', message: msg, statusCode: status });
                    return;
                }
                if (status < 200 || status >= 300) {
                    const msg = `网络异常(${status})`;
                    showToastIfNeeded(showErrorToast, msg);
                    resolve({ ok: false, errorType: 'network', message: msg, statusCode: status });
                    return;
                }
                const body = res.data;
                if (body === null || body === undefined || typeof body !== 'object') {
                    const msg = '数据格式异常';
                    showToastIfNeeded(showErrorToast, msg);
                    resolve({ ok: false, errorType: 'invalidData', message: msg, statusCode: status });
                    return;
                }
                const result = body;
                const code = result.code;
                if (code === 401) {
                    (0, storage_1.clearToken)();
                    (0, auth_nav_1.navigateToLoginPage)();
                    const msg = result.msg || '登录已失效';
                    showToastIfNeeded(showErrorToast, msg);
                    resolve({ ok: false, errorType: 'unauthorized', message: msg, statusCode: status });
                    return;
                }
                if (code !== 200) {
                    const msg = result.msg || '服务异常';
                    showToastIfNeeded(showErrorToast, msg);
                    resolve({ ok: false, errorType: 'server', message: msg, statusCode: status });
                    return;
                }
                resolve({ ok: true, data: result.data });
            },
            fail(err) {
                const msg = err.errMsg?.includes('timeout') ? '请求超时' : '网络不可用';
                showToastIfNeeded(showErrorToast, msg);
                resolve({ ok: false, errorType: 'network', message: msg });
            },
        });
    });
}
