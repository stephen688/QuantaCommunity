"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
const route_1 = require("../../constants/route");
const notification_service_1 = require("../../services/notification.service");
const user_auth_service_1 = require("../../services/user-auth.service");
const user_service_1 = require("../../services/user.service");
const user_1 = require("../../types/user");
const page_refresh_1 = require("../../utils/page-refresh");
const tab_bar_1 = require("../../utils/tab-bar");
function buildAssetEntries() {
    return [
        { key: 'posts', title: '我的发布', subtitle: '', icon: '', route: route_1.ROUTES.MY_CONTENT },
        { key: 'likes', title: '我的点赞', subtitle: '', icon: '', route: route_1.ROUTES.MY_LIKED },
        { key: 'collect', title: '我的收藏', subtitle: '', icon: '', route: route_1.ROUTES.MY_COLLECT },
        { key: 'history', title: '浏览历史', subtitle: '', icon: '', route: route_1.ROUTES.BROWSE_HISTORY },
    ];
}
function buildFunctionEntries(unread) {
    const badge = unread > 0 ? unread : undefined;
    return [
        {
            key: 'auth',
            title: '实名认证',
            subtitle: '完成认证后可使用更多能力',
            icon: '',
            route: `${route_1.ROUTES.PROFILE_EDIT}?intent=verify`,
        },
        {
            key: 'notify',
            title: '通知中心',
            subtitle: '活动与系统消息',
            icon: '',
            route: route_1.ROUTES.NOTIFICATION,
            badge,
        },
    ];
}
function profileErrorDescription(res) {
    const m = (res.message && res.message.trim()) || '';
    switch (res.errorType) {
        case 'unauthorized':
            return m || '登录状态已失效，请重新进入';
        case 'network':
            return m || '请检查网络后重试';
        case 'server':
            return m || '服务暂时不可用';
        case 'invalidData':
            return m || '资料数据异常';
        default:
            return m || '请稍后重试';
    }
}
function navigateEntry(route) {
    const path = (route || '').trim();
    if (!path) {
        return;
    }
    wx.navigateTo({ url: path });
}
function profileAuthToTone(authStatus) {
    const s = authStatus ?? 0;
    if (s === 2) {
        return 'success';
    }
    if (s === 1) {
        return 'warning';
    }
    if (s === 3) {
        return 'danger';
    }
    return 'neutral';
}
function identityTypeLabel(t) {
    if (t === 1) {
        return '在校成员';
    }
    if (t === 2) {
        return '历届校友';
    }
    return '';
}
function buildIdentityLine(dept, batch) {
    const d = (dept || '').trim();
    const b = (batch || '').trim();
    if (d && b) {
        return `${d} · ${b}`;
    }
    return d || b;
}
function resolveAuthBadge(authStatusVo, profile, user) {
    const audit = authStatusVo?.auditStatus;
    const profileAuth = profile?.authStatus ?? 0;
    // 审核表已通过，或主页展示态已为已认证（兼容 auth_status 未回写）
    if (audit === 1 || profileAuth === 2) {
        return { text: '已认证', tone: 'success' };
    }
    if (audit === 0 && profileAuth !== 2) {
        return { text: '审核中', tone: 'warning' };
    }
    if (audit === 2) {
        return { text: '审核不通过', tone: 'danger' };
    }
    if (profile) {
        const s = profileAuth;
        return {
            text: (0, user_1.userProfileAuthStatusText)(s),
            tone: profileAuthToTone(s),
        };
    }
    if (user.authStatus !== undefined && user.authStatus !== null) {
        return {
            text: (0, user_1.userProfileAuthStatusText)(user.authStatus),
            tone: profileAuthToTone(user.authStatus),
        };
    }
    if (audit === -1) {
        return { text: '未认证', tone: 'neutral' };
    }
    return { text: '', tone: 'neutral' };
}
/** 资料完整度计分字段：头像、昵称、院系、批次、实名认证通过 */
const PROFILE_COMPLETENESS_TOTAL = 5;
function hasText(v) {
    return !!(v && String(v).trim());
}
function computeProfileCompleteness(user, profile, authDetail, authAudit) {
    let filled = 0;
    if (hasText(user.avatarUrl)) {
        filled += 1;
    }
    if (hasText(user.nickName) || hasText(user.nickname)) {
        filled += 1;
    }
    const dept = authDetail?.quantaDepartment || profile?.quantaDepartment || user.quantaDepartment;
    if (hasText(dept)) {
        filled += 1;
    }
    const batch = authDetail?.quantaBatch || profile?.quantaBatch || user.quantaBatch;
    if (hasText(batch)) {
        filled += 1;
    }
    const verified = authAudit === 1 || profile?.authStatus === 2;
    if (verified) {
        filled += 1;
    }
    const percent = Math.round((filled / PROFILE_COMPLETENESS_TOTAL) * 100);
    let hint = '完善后校友更容易认出你';
    if (!verified) {
        hint = '完成实名认证，解锁更多校友能力';
    }
    else if (filled < PROFILE_COMPLETENESS_TOTAL) {
        hint = '补充头像、昵称与院系信息，让资料更完整';
    }
    return { filled, percent, hint };
}
function mergeMineHeaderFields(user, authStatusVo, profile, authDetail) {
    const dept = (authDetail?.quantaDepartment && String(authDetail.quantaDepartment).trim()) ||
        (profile?.quantaDepartment && String(profile.quantaDepartment).trim()) ||
        (user.quantaDepartment && String(user.quantaDepartment).trim()) ||
        '';
    const batch = (authDetail?.quantaBatch && String(authDetail.quantaBatch).trim()) ||
        (profile?.quantaBatch && String(profile.quantaBatch).trim()) ||
        (user.quantaBatch && String(user.quantaBatch).trim()) ||
        '';
    const { text: authBadgeText, tone: authBadgeTone } = resolveAuthBadge(authStatusVo, profile, user);
    const identityTypeText = authDetail ? identityTypeLabel(authDetail.identityType) : '';
    const identityLineText = buildIdentityLine(dept, batch);
    let headerStatPosts = -1;
    let headerStatFollowing = -1;
    let headerStatFollowers = -1;
    if (profile) {
        headerStatPosts = profile.contentCount ?? 0;
        headerStatFollowing = profile.followingCount ?? 0;
        headerStatFollowers = profile.followerCount ?? 0;
    }
    return {
        headerAuthBadgeText: authBadgeText,
        headerAuthBadgeTone: authBadgeTone,
        headerIdentityTypeText: identityTypeText,
        headerIdentityLineText: identityLineText,
        headerStatPosts,
        headerStatFollowing,
        headerStatFollowers,
    };
}
Page({
    _refreshSnapshot: (0, page_refresh_1.createPageRefreshSnapshot)(),
    data: {
        userInfo: null,
        profileLoading: true,
        profileErrorType: '',
        profileErrorMessage: '',
        unreadCount: 0,
        assetEntries: [],
        functionEntries: [],
        headerAuthBadgeText: '',
        headerAuthBadgeTone: 'neutral',
        headerIdentityTypeText: '',
        headerIdentityLineText: '',
        headerStatPosts: -1,
        headerStatFollowing: -1,
        headerStatFollowers: -1,
        profileCompletenessVisible: false,
        profileCompletenessPercent: 0,
        profileCompletenessFilled: 0,
        profileCompletenessTotal: PROFILE_COMPLETENESS_TOTAL,
        profileCompletenessHint: '',
        profileAuthVerified: false,
    },
    onLoad() {
        this.setData({
            assetEntries: buildAssetEntries(),
            functionEntries: buildFunctionEntries(0),
        });
        void this.loadUserProfile();
    },
    onShow() {
        this.syncTabBarState();
        void (0, tab_bar_1.refreshCustomTabBarUnread)(this);
        void this.loadUnreadCount();
        if ((0, page_refresh_1.shouldReloadOnShow)(this._refreshSnapshot, ['userProfile', 'userAuth'])) {
            (0, page_refresh_1.markRefreshConsumed)(this._refreshSnapshot, ['userProfile', 'userAuth']);
            void this.loadUserProfile();
        }
    },
    onProfileRetry() {
        void this.loadUserProfile();
        void this.loadUnreadCount();
    },
    onProfileEdit() {
        wx.navigateTo({ url: route_1.ROUTES.PROFILE_EDIT });
    },
    onCompletenessTap() {
        const url = this.data.profileAuthVerified
            ? route_1.ROUTES.PROFILE_EDIT
            : `${route_1.ROUTES.PROFILE_EDIT}?intent=verify`;
        wx.navigateTo({ url });
    },
    onAssetSelect(e) {
        const entry = e.detail?.entry;
        if (entry?.route) {
            navigateEntry(entry.route);
        }
    },
    onFunctionTap(e) {
        const entry = e.detail?.entry;
        if (entry?.route) {
            navigateEntry(entry.route);
        }
    },
    async loadUserProfile() {
        this.setData({
            profileLoading: true,
            profileErrorType: '',
            profileErrorMessage: '',
        });
        const [infoRes, authRes] = await Promise.all([(0, user_service_1.getUserInfo)(), (0, user_auth_service_1.getAuthStatus)()]);
        if (!infoRes.ok) {
            this.setData({
                profileLoading: false,
                profileErrorType: infoRes.errorType,
                profileErrorMessage: profileErrorDescription(infoRes),
                userInfo: null,
                headerAuthBadgeText: '',
                headerAuthBadgeTone: 'neutral',
                headerIdentityTypeText: '',
                headerIdentityLineText: '',
                headerStatPosts: -1,
                headerStatFollowing: -1,
                headerStatFollowers: -1,
                profileCompletenessVisible: false,
                profileCompletenessPercent: 0,
                profileCompletenessFilled: 0,
                profileCompletenessHint: '',
                profileAuthVerified: false,
            });
            return;
        }
        const user = infoRes.data;
        const uidRaw = user.userId === undefined || user.userId === null ? NaN : Number(user.userId);
        let profile = null;
        if (Number.isFinite(uidRaw) && uidRaw > 0) {
            const pr = await (0, user_service_1.getUserProfile)(uidRaw);
            if (pr.ok) {
                profile = pr.data;
            }
        }
        let authDetail = null;
        const audit = authRes.ok ? authRes.data?.auditStatus : undefined;
        if (authRes.ok && audit === 1) {
            const dr = await (0, user_auth_service_1.getAuthDetail)();
            if (dr.ok) {
                authDetail = dr.data;
            }
        }
        const authStatusVo = authRes.ok ? authRes.data ?? null : null;
        const headerFields = mergeMineHeaderFields(user, authStatusVo, profile, authDetail);
        const completeness = computeProfileCompleteness(user, profile, authDetail, audit);
        const profileAuthVerified = audit === 1 || profile?.authStatus === 2;
        (0, page_refresh_1.markRefreshConsumed)(this._refreshSnapshot, ['userProfile', 'userAuth']);
        this.setData({
            profileLoading: false,
            profileErrorType: '',
            profileErrorMessage: '',
            userInfo: user,
            ...headerFields,
            profileCompletenessVisible: true,
            profileCompletenessPercent: completeness.percent,
            profileCompletenessFilled: completeness.filled,
            profileCompletenessHint: completeness.hint,
            profileAuthVerified,
        });
    },
    async loadUnreadCount() {
        const res = await (0, notification_service_1.getUnreadCount)();
        if (!res.ok) {
            this.syncTabBarUnread(0);
            this.setData({
                unreadCount: 0,
                functionEntries: buildFunctionEntries(0),
            });
            return;
        }
        this.syncTabBarUnread(res.data);
        this.setData({
            unreadCount: res.data,
            functionEntries: buildFunctionEntries(res.data),
        });
    },
    syncTabBarState() {
        (0, tab_bar_1.syncCustomTabBarSelected)(this, 3);
    },
    syncTabBarUnread(count) {
        (0, tab_bar_1.getCustomTabBar)(this)?.setUnread?.(count);
    },
});
