const { isLoggedIn } = require('../../services/auth.service');
const { hideLoginSheet, showLoginSheet } = require('../../utils/login-sheet-bus');

Page({
  data: {
    statusBarHeight: 20,
    navBarHeight: 44,
    canBack: false,
    animateIn: false,
    motionReduced: false,
  },

  onLoad() {
    hideLoginSheet();
    const sysInfo = wx.getSystemInfoSync();
    const menuButton = wx.getMenuButtonBoundingClientRect();

    const statusBarHeight = sysInfo.statusBarHeight || 20;
    const navBarHeight = (menuButton.top - statusBarHeight) * 2 + menuButton.height;

    const pages = getCurrentPages();
    const canBack = pages.length > 1;

    let motionReduced = false;
    try {
      const app = getApp();
      motionReduced = Boolean(app?.globalData?.motionReduced);
    } catch {
      /* ignore */
    }

    this.setData({
      statusBarHeight,
      navBarHeight,
      canBack,
      motionReduced,
    });
  },

  onShow() {
    hideLoginSheet();
    let motionReduced = this.data.motionReduced;
    try {
      const app = getApp();
      motionReduced = Boolean(app?.globalData?.motionReduced);
    } catch {
      /* ignore */
    }
    if (motionReduced !== this.data.motionReduced) {
      this.setData({ motionReduced });
    }
    setTimeout(() => {
      this.setData({ animateIn: true });
    }, 50);
  },

  onHide() {
    this.setData({ animateIn: false });
  },

  onBack() {
    if (this.data.canBack) {
      wx.navigateBack();
    }
  },

  onLoginTap() {
    if (isLoggedIn()) {
      this.onLoginSuccess();
      return;
    }
    showLoginSheet({ force: true });
  },

  onSkipTap() {
    if (this.data.canBack) {
      wx.navigateBack();
    } else {
      wx.switchTab({ url: '/pages/home/index' });
    }
  },

  onLoginSuccess() {
    wx.showToast({ title: '登录成功', icon: 'success' });
    setTimeout(() => {
      wx.reLaunch({ url: '/pages/home/index' });
    }, 800);
  },

  onLoginSkip() {
    this.onSkipTap();
  },

  onAgreement() {
    wx.showToast({ title: '用户协议敬请期待', icon: 'none' });
  },

  onPrivacy() {
    wx.showToast({ title: '隐私政策敬请期待', icon: 'none' });
  },
});
