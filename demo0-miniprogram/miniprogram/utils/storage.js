"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.getToken = getToken;
exports.setToken = setToken;
exports.clearToken = clearToken;
exports.getLoginUserId = getLoginUserId;
exports.setLoginUserId = setLoginUserId;
exports.clearLoginUserId = clearLoginUserId;
exports.getStorageSafe = getStorageSafe;
exports.setStorageSafe = setStorageSafe;
const request_1 = require("../constants/request");
const LOGIN_USER_ID_KEY = 'LOGIN_USER_ID';
function getToken() {
    try {
        const v = wx.getStorageSync(request_1.TOKEN_KEY);
        if (typeof v !== 'string' || !v.trim()) {
            return undefined;
        }
        return v.trim();
    }
    catch {
        return undefined;
    }
}
function setToken(token) {
    wx.setStorageSync(request_1.TOKEN_KEY, token);
}
function clearToken() {
    try {
        wx.removeStorageSync(request_1.TOKEN_KEY);
    }
    catch {
        try {
            wx.setStorageSync(request_1.TOKEN_KEY, '');
        }
        catch {
            /* ignore */
        }
    }
}
function getLoginUserId() {
    const raw = getStorageSafe(LOGIN_USER_ID_KEY);
    const n = typeof raw === 'number' ? raw : Number(raw);
    if (Number.isFinite(n) && n > 0) {
        return Math.trunc(n);
    }
    const fromToken = extractUserIdFromToken(getToken());
    if (fromToken !== undefined) {
        setLoginUserId(fromToken);
    }
    return fromToken;
}
function setLoginUserId(userId) {
    if (!Number.isFinite(userId) || userId <= 0) {
        return;
    }
    setStorageSafe(LOGIN_USER_ID_KEY, Math.trunc(userId));
}
function clearLoginUserId() {
    try {
        wx.removeStorageSync(LOGIN_USER_ID_KEY);
    }
    catch {
        /* ignore */
    }
}
function extractUserIdFromToken(token) {
    if (!token) {
        return undefined;
    }
    const parts = token.split('.');
    if (parts.length < 2 || !parts[1]) {
        return undefined;
    }
    const payload = decodeBase64UrlJson(parts[1]);
    if (!payload || typeof payload !== 'object') {
        return undefined;
    }
    const uidRaw = payload.userId;
    const uid = typeof uidRaw === 'number' ? uidRaw : Number(uidRaw);
    if (!Number.isFinite(uid) || uid <= 0) {
        return undefined;
    }
    return Math.trunc(uid);
}
function decodeBase64UrlJson(segment) {
    try {
        const normalized = normalizeBase64(segment);
        const raw = base64Decode(normalized);
        if (!raw) {
            return undefined;
        }
        return JSON.parse(raw);
    }
    catch {
        return undefined;
    }
}
function normalizeBase64(segment) {
    const replaced = segment.replace(/-/g, '+').replace(/_/g, '/');
    const padding = replaced.length % 4;
    if (padding === 0) {
        return replaced;
    }
    return `${replaced}${'='.repeat(4 - padding)}`;
}
function base64Decode(base64) {
    try {
        const buffer = wx.base64ToArrayBuffer(base64);
        const bytes = new Uint8Array(buffer);
        let out = '';
        for (let i = 0; i < bytes.length; i += 1) {
            out += String.fromCharCode(bytes[i]);
        }
        return out;
    }
    catch {
        return undefined;
    }
}
function getStorageSafe(key) {
    try {
        return wx.getStorageSync(key);
    }
    catch {
        return undefined;
    }
}
function setStorageSafe(key, value) {
    try {
        wx.setStorageSync(key, value);
    }
    catch {
        /* ignore quota / serialize errors */
    }
}
