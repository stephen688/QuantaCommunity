import { DEV_CLEAR_TOKEN_ON_LAUNCH } from './constants/request';
import { isLoggedIn } from './services/auth.service';
import { hideLoginSheet, resetLoginSheetSession } from './utils/login-sheet-bus';
import { syncMotionReducedPreference } from './utils/motion-preference';
import { clearToken } from './utils/storage';

App<IAppOption>({
  globalData: {
    motionReduced: false,
  },
  onLaunch() {
    if (DEV_CLEAR_TOKEN_ON_LAUNCH) {
      clearToken();
      resetLoginSheetSession();
    }
    const reduced = syncMotionReducedPreference();
    this.globalData.motionReduced = reduced;
    setTimeout(() => {
      if (!isLoggedIn()) {
        hideLoginSheet();
        wx.reLaunch({ url: '/pages/login/index' });
      }
    }, 120);
  },
});

interface IAppOption {
  globalData: {
    motionReduced: boolean;
    /** 关注/取关后，关注 Tab 需重新拉取动态 */
    followFeedStale?: boolean;
  };
}
