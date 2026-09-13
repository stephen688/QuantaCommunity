"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
const real_name_service_1 = require("../../services/real-name.service");
const auth_intercept_bus_1 = require("../../utils/auth-intercept-bus");
Component({
    data: {
        show: false,
        panelIn: false,
        title: '完成实名认证',
        description: '发布、评论等互动需先完成校友实名认证，审核通过后即可使用。',
        reason: '',
    },
    lifetimes: {
        attached() {
            const self = this;
            self._unsubscribe = (0, auth_intercept_bus_1.subscribeAuthInterceptModal)((payload) => {
                if (payload) {
                    this.openModal(payload);
                }
                else {
                    this.closeModal();
                }
            });
        },
        detached() {
            const self = this;
            self._unsubscribe?.();
            if (self._closeTimer) {
                clearTimeout(self._closeTimer);
            }
        },
    },
    methods: {
        noop() { },
        openModal(payload) {
            const self = this;
            if (self._closeTimer) {
                clearTimeout(self._closeTimer);
                self._closeTimer = undefined;
            }
            this.setData({
                show: true,
                panelIn: false,
                title: payload.title?.trim() || '完成实名认证',
                description: payload.description?.trim() ||
                    '发布、评论等互动需先完成校友实名认证，审核通过后即可使用。',
                reason: payload.reason?.trim() || '',
            });
            setTimeout(() => {
                this.setData({ panelIn: true });
            }, 32);
        },
        closeModal() {
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
            return 220;
        },
        onMaskTap() {
            (0, auth_intercept_bus_1.hideAuthInterceptModal)();
            this.triggerEvent('cancel');
        },
        onCancelTap() {
            (0, auth_intercept_bus_1.hideAuthInterceptModal)();
            this.triggerEvent('cancel');
        },
        onConfirmTap() {
            (0, auth_intercept_bus_1.hideAuthInterceptModal)();
            (0, real_name_service_1.navigateToRealNameVerify)();
            this.triggerEvent('confirm');
        },
    },
});
