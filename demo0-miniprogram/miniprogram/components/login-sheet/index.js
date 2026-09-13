"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
const auth_service_1 = require("../../services/auth.service");
const user_service_1 = require("../../services/user.service");
const upload_service_1 = require("../../services/upload.service");
const app_refresh_bus_1 = require("../../utils/app-refresh-bus");
const login_sheet_bus_1 = require("../../utils/login-sheet-bus");
function isValidNickName(nickName) {
    const nick = nickName.trim();
    return nick.length > 0 && nick !== '微信用户';
}
Component({
    properties: {
        autoShow: {
            type: Boolean,
            value: false,
        },
    },
    data: {
        show: false,
        panelIn: false,
        loading: false,
        motionReduced: false,
        nickName: '',
        avatarTempPath: '',
        profileReady: false,
        nickNameError: false,
    },
    lifetimes: {
        attached() {
            const self = this;
            let motionReduced = false;
            try {
                const app = getApp();
                motionReduced = Boolean(app?.globalData?.motionReduced);
            }
            catch {
                /* ignore */
            }
            this.setData({ motionReduced });
            if (!this.properties.autoShow) {
                (0, login_sheet_bus_1.hideLoginSheet)();
            }
            self._unsubscribe = (0, login_sheet_bus_1.subscribeLoginSheet)((visible) => {
                if (visible) {
                    this.openSheet();
                }
                else {
                    this.closeSheet();
                }
            });
            if (this.properties.autoShow && !(0, auth_service_1.isLoggedIn)() && (0, login_sheet_bus_1.shouldAutoShowLoginSheet)()) {
                self._openTimer = setTimeout(() => this.openSheet(), 80);
            }
        },
        detached() {
            const self = this;
            self._unsubscribe?.();
            if (self._openTimer) {
                clearTimeout(self._openTimer);
            }
            if (self._closeTimer) {
                clearTimeout(self._closeTimer);
            }
        },
    },
    methods: {
        noop() { },
        openSheet() {
            const self = this;
            if ((0, auth_service_1.isLoggedIn)()) {
                (0, login_sheet_bus_1.hideLoginSheet)();
                this.triggerEvent('success');
                return;
            }
            if (self._closeTimer) {
                clearTimeout(self._closeTimer);
                self._closeTimer = undefined;
            }
            if (this.data.show && this.data.panelIn) {
                return;
            }
            this.setData({
                show: true,
                panelIn: false,
                nickName: '',
                avatarTempPath: '',
                profileReady: false,
                nickNameError: false,
            });
            if (self._openTimer) {
                clearTimeout(self._openTimer);
            }
            self._openTimer = setTimeout(() => {
                this.setData({ panelIn: true });
                self._openTimer = undefined;
            }, 48);
        },
        closeSheet() {
            const self = this;
            if (!this.data.show) {
                return;
            }
            this.setData({ panelIn: false });
            if (self._closeTimer) {
                clearTimeout(self._closeTimer);
            }
            const duration = this.getCloseDurationMs();
            self._closeTimer = setTimeout(() => {
                this.setData({ show: false });
                self._closeTimer = undefined;
            }, duration);
        },
        getCloseDurationMs() {
            try {
                const app = getApp();
                if (app?.globalData?.motionReduced) {
                    return 16;
                }
            }
            catch {
                /* ignore */
            }
            return 280;
        },
        syncProfileReady(nickName, avatarTempPath) {
            this.setData({
                profileReady: isValidNickName(nickName) && Boolean(avatarTempPath.trim()),
            });
        },
        async uploadAvatarAfterLogin(tempPath) {
            const uploadRes = await (0, upload_service_1.uploadImage)(tempPath);
            if (!uploadRes.ok) {
                wx.showToast({ title: uploadRes.message || '头像上传失败', icon: 'none' });
                return false;
            }
            if (!uploadRes.data) {
                wx.showToast({ title: '头像上传失败', icon: 'none' });
                return false;
            }
            const updateRes = await (0, user_service_1.updateUserInfo)({ avatarUrl: uploadRes.data });
            if (!updateRes.ok) {
                wx.showToast({ title: updateRes.message || '头像保存失败', icon: 'none' });
                return false;
            }
            (0, app_refresh_bus_1.mergeCachedUserInfo)({ avatarUrl: uploadRes.data });
            (0, app_refresh_bus_1.bumpAppRefresh)('userProfile');
            return true;
        },
        async runLogin(profile) {
            try {
                const ok = await (0, auth_service_1.loginWithWeChat)(profile);
                if (!ok) {
                    return;
                }
                const tempPath = (this.data.avatarTempPath || '').trim();
                if (tempPath) {
                    await this.uploadAvatarAfterLogin(tempPath);
                }
                this.triggerEvent('success');
            }
            catch {
                /* loginWithWeChat 已 toast */
            }
        },
        onChooseAvatar(e) {
            const path = (e.detail?.avatarUrl || '').trim();
            if (!path) {
                return;
            }
            this.setData({ avatarTempPath: path });
            this.syncProfileReady(this.data.nickName, path);
        },
        onNicknameInput(e) {
            const nickName = e.detail.value || '';
            this.setData({ nickName, nickNameError: false });
            this.syncProfileReady(nickName, this.data.avatarTempPath);
        },
        onNicknameReview(e) {
            const nickName = (e.detail?.value || '').trim();
            if (!nickName) {
                return;
            }
            this.setData({ nickName, nickNameError: false });
            this.syncProfileReady(nickName, this.data.avatarTempPath);
        },
        /** 允许：使用 chooseAvatar + nickname 填写的真实资料登录 */
        onAllow() {
            if (this.data.loading) {
                return;
            }
            const nickName = (this.data.nickName || '').trim();
            if (!isValidNickName(nickName)) {
                this.setData({ nickNameError: true });
                wx.showToast({ title: '请先点击填写昵称', icon: 'none' });
                return;
            }
            if (nickName.length > 20) {
                wx.showToast({ title: '昵称不能超过20字', icon: 'none' });
                return;
            }
            this.setData({ loading: true });
            void this.runLogin({ nickName }).finally(() => {
                this.setData({ loading: false });
            });
        },
        /** 拒绝：仅用 code 登录，不获取头像昵称 */
        onRefuse() {
            if (this.data.loading) {
                return;
            }
            this.setData({ loading: true });
            void this.runLogin(undefined).finally(() => {
                this.setData({ loading: false });
            });
        },
    },
});
