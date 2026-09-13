"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
const department_1 = require("../../constants/department");
const user_auth_service_1 = require("../../services/user-auth.service");
const user_service_1 = require("../../services/user.service");
const feed_error_1 = require("../../utils/feed-error");
const DEPT_PICKER_PLACEHOLDER = '请选择所属部门';
const DEPARTMENT_PICKER_RANGE = [DEPT_PICKER_PLACEHOLDER, ...department_1.QUANTA_DEPARTMENT_OPTIONS];
function resolveDepartmentPickerState(raw, range) {
    const s = (raw ?? '').trim();
    if (!s) {
        return { departmentPickerIndex: 0, departmentLegacyRaw: '', departmentPickerLabel: range[0] };
    }
    const fixIdx = (0, department_1.indexOfFixedQuantaDepartment)(s);
    if (fixIdx >= 0) {
        const departmentPickerIndex = fixIdx + 1;
        return {
            departmentPickerIndex,
            departmentLegacyRaw: '',
            departmentPickerLabel: range[departmentPickerIndex] ?? range[0],
        };
    }
    return { departmentPickerIndex: 0, departmentLegacyRaw: s, departmentPickerLabel: range[0] };
}
const AUTH_PENDING = 0;
const AUTH_APPROVED = 1;
const AUTH_REJECTED = 2;
const AUTH_NONE = -1;
function displayNick(u) {
    if (!u) {
        return '';
    }
    const n = (u.nickName || u.nickname || '').trim();
    return n;
}
function authStatusTitle(st) {
    if (st === AUTH_NONE) {
        return '尚未提交实名认证';
    }
    if (st === AUTH_PENDING) {
        return '审核中';
    }
    if (st === AUTH_APPROVED) {
        return '已通过实名认证';
    }
    if (st === AUTH_REJECTED) {
        return '认证未通过';
    }
    return '认证状态';
}
Page({
    data: {
        verifyMode: false,
        pageLoading: true,
        profileErrorType: '',
        profileErrorMessage: '',
        stateTitle: '',
        stateActionText: '',
        userInfo: null,
        nickName: '',
        images: [],
        submitting: false,
        uploading: false,
        uploadError: false,
        authStatus: null,
        authStatusText: '',
        approvedDetail: null,
        identityType: 1,
        realName: '',
        schoolId: '',
        quantaBatch: '',
        departmentPickerRange: DEPARTMENT_PICKER_RANGE,
        departmentPickerIndex: 0,
        departmentPickerLabel: DEPT_PICKER_PLACEHOLDER,
        departmentLegacyRaw: '',
        authSubmitting: false,
        focusedField: '',
        fieldErrors: {
            nickName: '',
            realName: '',
            schoolId: '',
            quantaBatch: '',
            department: '',
        },
        keyboardInset: 0,
    },
    onKeyboardHeightChange(res) {
        const h = Math.max(0, res.height || 0);
        if (h !== this.data.keyboardInset) {
            this.setData({ keyboardInset: h });
        }
    },
    onLoad(query) {
        const verifyMode = query.intent === 'verify';
        if (verifyMode) {
            wx.setNavigationBarTitle({ title: '实名认证' });
        }
        else {
            wx.setNavigationBarTitle({ title: '编辑资料' });
        }
        this.setData({ verifyMode });
        void this.reload();
    },
    onProfileRetry() {
        void this.reload();
    },
    onFieldFocus(e) {
        const field = e.currentTarget?.dataset?.field || '';
        this.setData({ focusedField: field });
    },
    onFieldBlur() {
        this.setData({ focusedField: '' });
    },
    clearFieldError(field) {
        if (!this.data.fieldErrors[field]) {
            return;
        }
        this.setData({ [`fieldErrors.${field}`]: '' });
    },
    setFieldError(field, message) {
        this.setData({ [`fieldErrors.${field}`]: message });
    },
    onNickInput(e) {
        this.clearFieldError('nickName');
        this.setData({ nickName: e.detail?.value ?? '' });
    },
    onImagesChange(e) {
        const raw = e.detail?.value;
        const list = Array.isArray(raw) ? raw.filter((x) => typeof x === 'string') : [];
        this.setData({ images: list.slice(0, 1) });
    },
    onUploadStateChange(e) {
        this.setData({
            uploading: e.detail?.uploading === true,
            uploadError: e.detail?.hasError === true,
        });
    },
    onIdentityCampus() {
        this.setData({ identityType: 1 });
    },
    onIdentityAlumni() {
        this.setData({ identityType: 2 });
    },
    onRealNameInput(e) {
        this.clearFieldError('realName');
        this.setData({ realName: e.detail?.value ?? '' });
    },
    onSchoolIdInput(e) {
        this.clearFieldError('schoolId');
        this.setData({ schoolId: e.detail?.value ?? '' });
    },
    onBatchInput(e) {
        this.clearFieldError('quantaBatch');
        this.setData({ quantaBatch: e.detail?.value ?? '' });
    },
    onDepartmentPickerChange(e) {
        const idx = Number(e.detail.value);
        const range = this.data.departmentPickerRange;
        const safe = Number.isFinite(idx) ? Math.max(0, Math.min(idx, range.length - 1)) : 0;
        this.clearFieldError('department');
        this.setData({
            departmentPickerIndex: safe,
            departmentPickerLabel: range[safe] ?? range[0],
            departmentLegacyRaw: safe > 0 ? '' : this.data.departmentLegacyRaw,
        });
    },
    async onSubmitProfile() {
        if (this.data.submitting || this.data.uploading) {
            return;
        }
        const nick = this.data.nickName.trim();
        if (!nick) {
            this.setFieldError('nickName', '昵称不能为空');
            return;
        }
        if (nick.length > 20) {
            this.setFieldError('nickName', '昵称不能超过 20 字');
            return;
        }
        if (this.data.uploadError) {
            wx.showToast({ title: '头像上传失败，请点击重试后再保存', icon: 'none' });
            return;
        }
        const payload = { nickName: nick };
        const av = (this.data.images[0] || '').trim();
        if (av) {
            if (!av.startsWith('http://') && !av.startsWith('https://')) {
                wx.showToast({ title: '头像上传未完成，请等待上传成功后再保存', icon: 'none' });
                return;
            }
            payload.avatarUrl = av;
        }
        this.setData({ submitting: true });
        const res = await (0, user_service_1.updateUserInfo)(payload);
        this.setData({ submitting: false });
        if (!res.ok) {
            wx.showToast({ title: res.message || '保存失败', icon: 'none' });
            return;
        }
        const refreshed = await (0, user_service_1.getUserInfo)();
        if (refreshed.ok) {
            this.setData({
                userInfo: refreshed.data,
                nickName: displayNick(refreshed.data),
                images: (refreshed.data.avatarUrl || '').trim()
                    ? [(refreshed.data.avatarUrl || '').trim()]
                    : [],
            });
        }
        wx.showToast({ title: '已保存', icon: 'success' });
        setTimeout(() => wx.navigateBack(), 400);
    },
    async onSubmitAuth() {
        if (this.data.authSubmitting) {
            return;
        }
        const st = this.data.authStatus?.auditStatus;
        if (st === AUTH_PENDING) {
            wx.showToast({ title: '审核中，请耐心等待', icon: 'none' });
            return;
        }
        if (st === AUTH_APPROVED) {
            wx.showToast({ title: '已完成认证', icon: 'none' });
            return;
        }
        const identityType = this.data.identityType;
        if (identityType !== 1 && identityType !== 2) {
            wx.showToast({ title: '请选择身份类型', icon: 'none' });
            return;
        }
        const realName = this.data.realName.trim();
        const schoolId = this.data.schoolId.trim();
        const quantaBatch = this.data.quantaBatch.trim();
        let hasError = false;
        if (!realName) {
            this.setFieldError('realName', '请填写真实姓名');
            hasError = true;
        }
        if (!schoolId) {
            this.setFieldError('schoolId', '请填写学号或校友编号');
            hasError = true;
        }
        if (!quantaBatch) {
            this.setFieldError('quantaBatch', '请填写 Quanta 届');
            hasError = true;
        }
        if (this.data.departmentPickerIndex <= 0) {
            this.setFieldError('department', '请选择所属部门');
            hasError = true;
        }
        if (hasError) {
            wx.showToast({ title: '身份信息未填完整', icon: 'none' });
            return;
        }
        const quantaDepartment = department_1.QUANTA_DEPARTMENT_OPTIONS[this.data.departmentPickerIndex - 1];
        this.setData({ authSubmitting: true });
        const res = await (0, user_auth_service_1.submitUserAuth)({
            identityType,
            realName,
            schoolId,
            quantaBatch,
            quantaDepartment,
        });
        this.setData({ authSubmitting: false });
        if (!res.ok) {
            wx.showToast({ title: res.message || '提交失败', icon: 'none' });
            return;
        }
        wx.showToast({ title: '已提交，待审核', icon: 'success' });
        await this.loadVerify();
    },
    async reload() {
        this.setData({
            pageLoading: true,
            profileErrorType: '',
            profileErrorMessage: '',
            stateTitle: '',
            stateActionText: '',
        });
        if (this.data.verifyMode) {
            await this.loadVerify();
        }
        else {
            await this.loadProfile();
        }
    },
    async loadProfile() {
        const res = await (0, user_service_1.getUserInfo)();
        if (!res.ok) {
            const err = (0, feed_error_1.feedFullScreenError)(res);
            this.setData({
                pageLoading: false,
                userInfo: null,
                profileErrorType: err.errorType,
                stateTitle: err.stateTitle,
                profileErrorMessage: err.errorMessage,
                stateActionText: err.stateActionText,
            });
            return;
        }
        const u = res.data;
        const nick = displayNick(u);
        const av = (u.avatarUrl || '').trim();
        this.setData({
            pageLoading: false,
            profileErrorType: '',
            profileErrorMessage: '',
            stateTitle: '',
            stateActionText: '',
            userInfo: u,
            nickName: nick,
            images: av ? [av] : [],
        });
    },
    async loadVerify() {
        const res = await (0, user_auth_service_1.getAuthStatus)();
        if (!res.ok) {
            const err = (0, feed_error_1.feedFullScreenError)(res);
            this.setData({
                pageLoading: false,
                authStatus: null,
                profileErrorType: err.errorType,
                stateTitle: err.stateTitle,
                profileErrorMessage: err.errorMessage,
                stateActionText: err.stateActionText,
                authStatusText: '',
            });
            return;
        }
        const authStatus = res.data;
        const st = authStatus.auditStatus ?? AUTH_NONE;
        let approvedDetail = null;
        if (st === AUTH_APPROVED) {
            const d = await (0, user_auth_service_1.getAuthDetail)();
            if (d.ok) {
                approvedDetail = d.data;
            }
        }
        let deptPrefill = '';
        if (this.data.verifyMode && (st === AUTH_NONE || st === AUTH_REJECTED)) {
            const ui = await (0, user_service_1.getUserInfo)();
            if (ui.ok) {
                deptPrefill = (ui.data.quantaDepartment ?? '').trim();
            }
        }
        const deptState = resolveDepartmentPickerState(deptPrefill, DEPARTMENT_PICKER_RANGE);
        this.setData({
            pageLoading: false,
            profileErrorType: '',
            profileErrorMessage: '',
            stateTitle: '',
            stateActionText: '',
            authStatus,
            authStatusText: authStatusTitle(st),
            approvedDetail,
            ...deptState,
        });
    },
});
