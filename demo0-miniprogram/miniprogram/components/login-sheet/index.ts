import { isLoggedIn, loginWithWeChat, type WeChatProfilePayload } from '../../services/auth.service';
import { updateUserInfo } from '../../services/user.service';
import { uploadImage } from '../../services/upload.service';
import { bumpAppRefresh, mergeCachedUserInfo } from '../../utils/app-refresh-bus';
import {
  hideLoginSheet,
  shouldAutoShowLoginSheet,
  subscribeLoginSheet,
} from '../../utils/login-sheet-bus';

type LoginSheetInstance = WechatMiniprogram.Component.TrivialInstance & {
  _unsubscribe?: () => void;
  _openTimer?: ReturnType<typeof setTimeout>;
  _closeTimer?: ReturnType<typeof setTimeout>;
};

function isValidNickName(nickName: string): boolean {
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
      const self = this as LoginSheetInstance;
      let motionReduced = false;
      try {
        const app = getApp<{ globalData?: { motionReduced?: boolean } }>();
        motionReduced = Boolean(app?.globalData?.motionReduced);
      } catch {
        /* ignore */
      }
      this.setData({ motionReduced });

      if (!this.properties.autoShow) {
        hideLoginSheet();
      }

      self._unsubscribe = subscribeLoginSheet((visible) => {
        if (visible) {
          this.openSheet();
        } else {
          this.closeSheet();
        }
      });

      if (this.properties.autoShow && !isLoggedIn() && shouldAutoShowLoginSheet()) {
        self._openTimer = setTimeout(() => this.openSheet(), 80);
      }
    },

    detached() {
      const self = this as LoginSheetInstance;
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
    noop() {},

    openSheet() {
      const self = this as LoginSheetInstance;
      if (isLoggedIn()) {
        hideLoginSheet();
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
      const self = this as LoginSheetInstance;
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
      return 280;
    },

    syncProfileReady(nickName: string, avatarTempPath: string) {
      this.setData({
        profileReady: isValidNickName(nickName) && Boolean(avatarTempPath.trim()),
      });
    },

    async uploadAvatarAfterLogin(tempPath: string): Promise<boolean> {
      const uploadRes = await uploadImage(tempPath);
      if (!uploadRes.ok) {
        wx.showToast({ title: uploadRes.message || '头像上传失败', icon: 'none' });
        return false;
      }
      if (!uploadRes.data) {
        wx.showToast({ title: '头像上传失败', icon: 'none' });
        return false;
      }
      const updateRes = await updateUserInfo({ avatarUrl: uploadRes.data });
      if (!updateRes.ok) {
        wx.showToast({ title: updateRes.message || '头像保存失败', icon: 'none' });
        return false;
      }
      mergeCachedUserInfo({ avatarUrl: uploadRes.data });
      bumpAppRefresh('userProfile');
      return true;
    },

    async runLogin(profile?: WeChatProfilePayload) {
      try {
        const ok = await loginWithWeChat(profile);
        if (!ok) {
          return;
        }
        const tempPath = (this.data.avatarTempPath || '').trim();
        if (tempPath) {
          await this.uploadAvatarAfterLogin(tempPath);
        }
        this.triggerEvent('success');
      } catch {
        /* loginWithWeChat 已 toast */
      }
    },

    onChooseAvatar(e: WechatMiniprogram.CustomEvent<{ avatarUrl?: string }>) {
      const path = (e.detail?.avatarUrl || '').trim();
      if (!path) {
        return;
      }
      this.setData({ avatarTempPath: path });
      this.syncProfileReady(this.data.nickName, path);
    },

    onNicknameInput(e: WechatMiniprogram.Input) {
      const nickName = e.detail.value || '';
      this.setData({ nickName, nickNameError: false });
      this.syncProfileReady(nickName, this.data.avatarTempPath);
    },

    onNicknameReview(e: WechatMiniprogram.Input) {
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
