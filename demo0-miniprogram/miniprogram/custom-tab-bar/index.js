"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
const route_1 = require("../constants/route");
const real_name_service_1 = require("../services/real-name.service");
const TAB_ITEMS = [
    { pagePath: route_1.ROUTES.HOME, isTab: true },
    { pagePath: route_1.ROUTES.FOLLOW, isTab: true },
    { pagePath: route_1.ROUTES.NOTIFICATION, isTab: true },
    { pagePath: route_1.ROUTES.MINE, isTab: true },
];
Component({
    data: {
        selected: 0,
        badgeText: '',
        safeBottom: 0,
    },
    lifetimes: {
        attached() {
            let safeBottom = 0;
            try {
                const win = wx.getWindowInfo();
                if (win.safeArea && win.screenHeight) {
                    safeBottom = Math.max(0, win.screenHeight - win.safeArea.bottom);
                }
            }
            catch {
                safeBottom = 0;
            }
            this.setData({ safeBottom });
        },
    },
    methods: {
        onTabTap(e) {
            const index = Number(e.currentTarget.dataset.index);
            if (!Number.isInteger(index) || index < 0 || index >= TAB_ITEMS.length) {
                return;
            }
            const item = TAB_ITEMS[index];
            this.setData({ selected: index });
            if (item.isTab) {
                wx.switchTab({ url: item.pagePath });
            }
        },
        async onPublishTap() {
            const ok = await (0, real_name_service_1.ensureRealNameVerified)({
                reason: '发布内容',
                description: '发布帖子需先完成校友实名认证，审核通过后即可发布。',
            });
            if (!ok) {
                return;
            }
            wx.navigateTo({ url: route_1.ROUTES.PUBLISH });
        },
        setSelected(index) {
            if (!Number.isInteger(index) || index < 0 || index >= TAB_ITEMS.length) {
                return;
            }
            this.setData({ selected: index });
        },
        setUnread(count) {
            const value = Number(count);
            if (!Number.isFinite(value) || value <= 0) {
                this.setData({ badgeText: '' });
                return;
            }
            const n = Math.floor(value);
            this.setData({ badgeText: n > 99 ? '99+' : String(n) });
        },
    },
});
