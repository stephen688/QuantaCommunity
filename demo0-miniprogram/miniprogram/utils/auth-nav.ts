import { hideLoginSheet } from './login-sheet-bus';

const LOGIN_ROUTE = '/pages/login/index';

let navigating = false;

/** 跳转登录页（不弹出资料授权层，需用户在登录页点击「微信登录」） */
export function navigateToLoginPage(): void {
  if (navigating) {
    return;
  }
  const pages = getCurrentPages();
  const current = pages[pages.length - 1];
  if (current?.route === 'pages/login/index') {
    return;
  }
  navigating = true;
  hideLoginSheet();
  wx.navigateTo({
    url: LOGIN_ROUTE,
    complete: () => {
      navigating = false;
    },
  });
}
