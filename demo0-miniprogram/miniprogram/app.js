"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
const request_1 = require("./constants/request");
const auth_service_1 = require("./services/auth.service");
const login_sheet_bus_1 = require("./utils/login-sheet-bus");
const motion_preference_1 = require("./utils/motion-preference");
const storage_1 = require("./utils/storage");
App({
    globalData: {
        motionReduced: false,
    },
    onLaunch() {
        if (request_1.DEV_CLEAR_TOKEN_ON_LAUNCH) {
            (0, storage_1.clearToken)();
            (0, login_sheet_bus_1.resetLoginSheetSession)();
        }
        const reduced = (0, motion_preference_1.syncMotionReducedPreference)();
        this.globalData.motionReduced = reduced;
        setTimeout(() => {
            if (!(0, auth_service_1.isLoggedIn)()) {
                (0, login_sheet_bus_1.hideLoginSheet)();
                wx.reLaunch({ url: '/pages/login/index' });
            }
        }, 120);
    },
});
