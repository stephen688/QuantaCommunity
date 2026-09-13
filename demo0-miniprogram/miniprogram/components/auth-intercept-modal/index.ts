import { navigateToRealNameVerify } from '../../services/real-name.service';
import {
  hideAuthInterceptModal,
  subscribeAuthInterceptModal,
  type AuthInterceptPayload,
} from '../../utils/auth-intercept-bus';

type ModalInstance = WechatMiniprogram.Component.TrivialInstance & {
  _unsubscribe?: () => void;
  _closeTimer?: ReturnType<typeof setTimeout>;
};

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
      const self = this as ModalInstance;
      self._unsubscribe = subscribeAuthInterceptModal((payload) => {
        if (payload) {
          this.openModal(payload);
        } else {
          this.closeModal();
        }
      });
    },

    detached() {
      const self = this as ModalInstance;
      self._unsubscribe?.();
      if (self._closeTimer) {
        clearTimeout(self._closeTimer);
      }
    },
  },

  methods: {
    noop() {},

    openModal(payload: AuthInterceptPayload) {
      const self = this as ModalInstance;
      if (self._closeTimer) {
        clearTimeout(self._closeTimer);
        self._closeTimer = undefined;
      }
      this.setData({
        show: true,
        panelIn: false,
        title: payload.title?.trim() || '完成实名认证',
        description:
          payload.description?.trim() ||
          '发布、评论等互动需先完成校友实名认证，审核通过后即可使用。',
        reason: payload.reason?.trim() || '',
      });
      setTimeout(() => {
        this.setData({ panelIn: true });
      }, 32);
    },

    closeModal() {
      const self = this as ModalInstance;
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

    getCloseDurationMs(): number {
      try {
        const app = getApp<{ globalData?: { motionReduced?: boolean } }>();
        if (app?.globalData?.motionReduced) {
          return 16;
        }
      } catch {
        /* ignore */
      }
      return 220;
    },

    onMaskTap() {
      hideAuthInterceptModal();
      this.triggerEvent('cancel');
    },

    onCancelTap() {
      hideAuthInterceptModal();
      this.triggerEvent('cancel');
    },

    onConfirmTap() {
      hideAuthInterceptModal();
      navigateToRealNameVerify();
      this.triggerEvent('confirm');
    },
  },
});
