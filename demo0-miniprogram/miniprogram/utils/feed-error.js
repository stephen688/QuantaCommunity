"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.feedFullScreenError = feedFullScreenError;
/** 首页 / 关注流首屏 StateBlock：标题、说明、按钮文案（对齐 Step 10） */
function feedFullScreenError(res) {
    const action = '重新加载';
    const msg = (res.message && String(res.message).trim()) || '';
    switch (res.errorType) {
        case 'unauthorized':
            return {
                errorType: 'unauthorized',
                stateTitle: '登录状态已失效',
                errorMessage: msg || '请重新进入',
                stateActionText: action,
            };
        case 'network': {
            const hint = msg.includes('502') || msg.includes('503')
                ? '后端未启动或 ngrok 未连通，请先启动 demo0（9191）'
                : msg || '请检查网络后重试';
            return {
                errorType: 'network',
                stateTitle: '网络异常',
                errorMessage: hint,
                stateActionText: action,
            };
        }
        case 'server':
            return {
                errorType: 'server',
                stateTitle: '服务暂时不可用',
                errorMessage: msg || '服务暂时不可用',
                stateActionText: action,
            };
        case 'invalidData':
            return {
                errorType: 'invalidData',
                stateTitle: '数据异常',
                errorMessage: msg || '返回数据格式异常',
                stateActionText: action,
            };
        default:
            return {
                errorType: res.errorType,
                stateTitle: '加载失败',
                errorMessage: msg || '请稍后重试',
                stateActionText: action,
            };
    }
}
