"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
Component({
    properties: {
        loading: {
            type: Boolean,
            value: false,
        },
        user: {
            type: Object,
            value: undefined,
        },
        errorType: {
            type: String,
            value: '',
        },
        errorMessage: {
            type: String,
            value: '',
        },
        authBadgeText: {
            type: String,
            value: '',
        },
        authBadgeTone: {
            type: String,
            value: 'neutral',
        },
        identityTypeText: {
            type: String,
            value: '',
        },
        identityLineText: {
            type: String,
            value: '',
        },
        statPosts: {
            type: Number,
            value: -1,
        },
        statFollowing: {
            type: Number,
            value: -1,
        },
        statFollowers: {
            type: Number,
            value: -1,
        },
    },
    data: {
        displayName: '未登录用户',
        avatarLetter: '未',
        showAvatar: false,
        avatarUrl: '',
        bioText: '',
        hasError: false,
        showStats: false,
    },
    observers: {
        user() {
            this.applyUser();
        },
        errorType(t) {
            this.setData({ hasError: Boolean(t) });
        },
        statPosts() {
            this.applyStatsVisibility();
        },
        statFollowing() {
            this.applyStatsVisibility();
        },
        statFollowers() {
            this.applyStatsVisibility();
        },
    },
    lifetimes: {
        attached() {
            this.applyUser();
            this.applyStatsVisibility();
            this.setData({ hasError: Boolean(this.data.errorType) });
        },
    },
    methods: {
        applyUser() {
            const u = this.data.user;
            const name = (u?.nickName && u.nickName.trim()) ||
                (u?.nickname && u.nickname.trim()) ||
                '未登录用户';
            const url = typeof u?.avatarUrl === 'string' && u.avatarUrl.trim() ? u.avatarUrl.trim() : '';
            const bio = (u?.bio && u.bio.trim()) || '';
            this.setData({
                displayName: name,
                avatarLetter: name.slice(0, 1),
                avatarUrl: url,
                showAvatar: Boolean(url),
                bioText: bio,
            });
        },
        applyStatsVisibility() {
            const p = Number(this.data.statPosts);
            const f = Number(this.data.statFollowing);
            const g = Number(this.data.statFollowers);
            const show = Number.isFinite(p) &&
                p >= 0 &&
                Number.isFinite(f) &&
                f >= 0 &&
                Number.isFinite(g) &&
                g >= 0;
            this.setData({ showStats: show });
        },
        onEditTap() {
            this.triggerEvent('edit');
        },
        onRetryTap() {
            this.triggerEvent('retry');
        },
    },
});
