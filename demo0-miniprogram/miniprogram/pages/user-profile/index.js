"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
const request_1 = require("../../constants/request");
const route_1 = require("../../constants/route");
const follow_service_1 = require("../../services/follow.service");
const user_auth_service_1 = require("../../services/user-auth.service");
const user_service_1 = require("../../services/user.service");
const detail_shared_1 = require("../../utils/detail-shared");
const feed_error_1 = require("../../utils/feed-error");
const page_refresh_1 = require("../../utils/page-refresh");
const content_card_list_1 = require("../../utils/content-card-list");
const content_card_navigate_1 = require("../../utils/content-card-navigate");
function parseUserId(raw) {
    if (raw === undefined || raw === null || raw === '') {
        return null;
    }
    const n = Number(raw);
    if (!Number.isFinite(n) || n <= 0 || !Number.isInteger(n)) {
        return null;
    }
    return n;
}
async function mapProfileWithAuthAudit(vo) {
    let audit;
    if (vo.isSelf === true) {
        const authRes = await (0, user_auth_service_1.getAuthStatus)();
        if (authRes.ok && authRes.data?.auditStatus !== undefined && authRes.data?.auditStatus !== null) {
            audit = authRes.data.auditStatus;
        }
    }
    return (0, user_service_1.mapUserProfileVOToModel)(vo, audit);
}
Page({
    data: {
        invalidQuery: false,
        userIdNum: 0,
        profile: null,
        list: [],
        loading: true,
        refreshing: false,
        loadingMore: false,
        finished: false,
        errorType: '',
        errorMessage: '',
        stateTitle: '',
        stateActionText: '',
        needAuthVerify: false,
        listLoadError: false,
        listErrorMessage: '',
        listNeedAuthVerify: false,
        current: 1,
        pageSize: request_1.DEFAULT_PAGE_SIZE,
        loadMoreError: false,
        followLoading: false,
    },
    _loadMoreLock: false,
    _refreshSnapshot: (0, page_refresh_1.createPageRefreshSnapshot)(),
    _pageHasShown: false,
    onLoad(options) {
        const uid = parseUserId(options.userId);
        if (uid === null) {
            this.setData({
                invalidQuery: true,
                loading: false,
            });
            return;
        }
        this.setData({
            userIdNum: uid,
            invalidQuery: false,
        });
        void this.loadInitial(false);
    },
    onShow() {
        if (!this._pageHasShown) {
            this._pageHasShown = true;
            return;
        }
        if (!this.data.profile?.isSelf ||
            !(0, page_refresh_1.shouldReloadOnShow)(this._refreshSnapshot, ['userProfile', 'userAuth'])) {
            return;
        }
        (0, page_refresh_1.markRefreshConsumed)(this._refreshSnapshot, ['userProfile', 'userAuth']);
        void this.loadInitial(true);
    },
    onPullDownRefresh() {
        if (this.data.invalidQuery) {
            wx.stopPullDownRefresh();
            return;
        }
        this.setData({ refreshing: true });
        void this.loadInitial(true);
    },
    onReachBottom() {
        if (this.data.invalidQuery ||
            this.data.listLoadError ||
            this.data.loading ||
            this.data.refreshing ||
            this.data.loadingMore ||
            this.data.finished ||
            !this.data.list.length) {
            return;
        }
        void this.loadMore();
    },
    onStateAction() {
        if (this.data.needAuthVerify) {
            wx.navigateTo({ url: `${route_1.ROUTES.PROFILE_EDIT}?intent=verify` });
            return;
        }
        void this.loadInitial(false);
    },
    onListStateAction() {
        if (this.data.listNeedAuthVerify) {
            wx.navigateTo({ url: `${route_1.ROUTES.PROFILE_EDIT}?intent=verify` });
            return;
        }
        void this.reloadListFirstPage();
    },
    onFooterRetry() {
        this.setData({ loadMoreError: false });
        void this.loadMore();
    },
    onEditProfile() {
        wx.navigateTo({ url: route_1.ROUTES.PROFILE_EDIT });
    },
    async onFollowTap() {
        const p = this.data.profile;
        const uid = this.data.userIdNum;
        if (!p || p.isSelf || this.data.followLoading || !uid) {
            return;
        }
        this.setData({ followLoading: true });
        try {
            const res = await (0, follow_service_1.setFollowUser)(uid, p.isFollowed !== true);
            if (!res.ok) {
                wx.showToast({
                    title: (res.message && String(res.message).trim()) || '操作失败',
                    icon: 'none',
                });
                return;
            }
            const d = res.data;
            const nextFollowed = d.isFollowed === true;
            let fc = p.followerCount;
            const rawFc = d.followerCount ?? d.followCount;
            if (rawFc !== undefined && rawFc !== null && Number.isFinite(Number(rawFc))) {
                fc = Math.max(0, Number(rawFc));
            }
            else {
                fc = nextFollowed ? fc + 1 : Math.max(0, fc - 1);
            }
            this.setData({
                'profile.isFollowed': nextFollowed,
                'profile.followerCount': fc,
            });
        }
        catch {
            wx.showToast({ title: '网络异常', icon: 'none' });
        }
        finally {
            this.setData({ followLoading: false });
        }
    },
    onCardTap(e) {
        (0, content_card_navigate_1.navigateFromContentCardEvent)(e);
    },
    onCardInteractionChange(e) {
        const next = e.detail?.item;
        if (!next?.contentId) {
            return;
        }
        this.setData({
            list: (0, content_card_list_1.patchContentCardInList)(this.data.list, next),
        });
    },
    async loadInitial(fromRefresh) {
        const uid = this.data.userIdNum;
        if (!uid || this.data.invalidQuery) {
            return;
        }
        if (!fromRefresh) {
            this.setData({
                loading: true,
                profile: null,
                list: [],
                current: 1,
                finished: false,
                errorType: '',
                errorMessage: '',
                stateTitle: '',
                stateActionText: '',
                needAuthVerify: false,
                listLoadError: false,
                listErrorMessage: '',
                listNeedAuthVerify: false,
                loadMoreError: false,
            });
        }
        try {
            const [profileRes, contentsRes] = await Promise.all([
                (0, user_service_1.getUserProfile)(uid),
                (0, user_service_1.getUserPublicContents)(uid, 1, this.data.pageSize),
            ]);
            if (!fromRefresh) {
                if (!profileRes.ok) {
                    if ((0, detail_shared_1.isRealNameRequiredMessage)(profileRes.message || '')) {
                        this.setData({
                            loading: false,
                            errorType: 'server',
                            stateTitle: '需完成实名认证',
                            errorMessage: profileRes.message || '请先完成实名认证',
                            stateActionText: '去认证',
                            needAuthVerify: true,
                            profile: null,
                            list: [],
                            listLoadError: false,
                            listNeedAuthVerify: false,
                        });
                        return;
                    }
                    const err = (0, feed_error_1.feedFullScreenError)(profileRes);
                    this.setData({
                        loading: false,
                        profile: null,
                        list: [],
                        listLoadError: false,
                        listNeedAuthVerify: false,
                        needAuthVerify: false,
                        errorType: err.errorType,
                        stateTitle: err.stateTitle,
                        errorMessage: err.errorMessage,
                        stateActionText: err.stateActionText,
                    });
                    return;
                }
                const model = await mapProfileWithAuthAudit(profileRes.data);
                if (!model) {
                    this.setData({
                        loading: false,
                        profile: null,
                        list: [],
                        listLoadError: false,
                        listNeedAuthVerify: false,
                        needAuthVerify: false,
                        errorType: 'invalidData',
                        stateTitle: '数据异常',
                        errorMessage: '用户主页数据异常',
                        stateActionText: '重新加载',
                    });
                    return;
                }
                wx.setNavigationBarTitle({ title: model.nickname || '用户主页' });
                if (!contentsRes.ok) {
                    if ((0, detail_shared_1.isRealNameRequiredMessage)(contentsRes.message || '')) {
                        this.setData({
                            loading: false,
                            profile: model,
                            list: [],
                            current: 1,
                            finished: true,
                            listLoadError: true,
                            listErrorMessage: contentsRes.message || '请先完成实名认证',
                            listNeedAuthVerify: true,
                            needAuthVerify: false,
                        });
                        return;
                    }
                    this.setData({
                        loading: false,
                        profile: model,
                        list: [],
                        current: 1,
                        finished: true,
                        listLoadError: true,
                        listErrorMessage: (contentsRes.message && String(contentsRes.message).trim()) || '加载失败',
                        listNeedAuthVerify: false,
                        needAuthVerify: false,
                    });
                    return;
                }
                const { list, hasMore } = contentsRes.data;
                (0, page_refresh_1.markRefreshConsumed)(this._refreshSnapshot, ['userProfile', 'userAuth']);
                this.setData({
                    loading: false,
                    profile: model,
                    list,
                    current: 1,
                    finished: !hasMore || list.length < this.data.pageSize,
                    listLoadError: false,
                    listErrorMessage: '',
                    listNeedAuthVerify: false,
                    needAuthVerify: false,
                    errorType: '',
                    errorMessage: '',
                    stateTitle: '',
                    stateActionText: '',
                });
                return;
            }
            let profileToast = false;
            let contentsToast = false;
            if (profileRes.ok) {
                const model = await mapProfileWithAuthAudit(profileRes.data);
                if (model) {
                    wx.setNavigationBarTitle({ title: model.nickname || '用户主页' });
                    this.setData({ profile: model });
                }
                else if (this.data.profile) {
                    profileToast = true;
                }
            }
            else if (this.data.profile) {
                profileToast = true;
            }
            if (contentsRes.ok) {
                const { list, hasMore } = contentsRes.data;
                this.setData({
                    list,
                    current: 1,
                    finished: !hasMore || list.length < this.data.pageSize,
                    listLoadError: false,
                    listErrorMessage: '',
                    listNeedAuthVerify: false,
                    loadMoreError: false,
                });
            }
            else if (this.data.list.length) {
                contentsToast = true;
            }
            else {
                const msg = (contentsRes.message && String(contentsRes.message).trim()) || '加载失败';
                this.setData({
                    list: [],
                    current: 1,
                    finished: true,
                    listLoadError: true,
                    listErrorMessage: msg,
                    listNeedAuthVerify: (0, detail_shared_1.isRealNameRequiredMessage)(msg),
                });
            }
            if (profileToast) {
                wx.showToast({ title: '资料刷新失败', icon: 'none' });
            }
            if (contentsToast) {
                wx.showToast({ title: '内容刷新失败', icon: 'none' });
            }
        }
        catch {
            if (!fromRefresh) {
                this.setData({
                    loading: false,
                    profile: null,
                    list: [],
                    listLoadError: false,
                    listNeedAuthVerify: false,
                    needAuthVerify: false,
                    ...(0, feed_error_1.feedFullScreenError)({
                        ok: false,
                        errorType: 'network',
                        message: '请求异常，请稍后重试',
                    }),
                });
            }
            else {
                wx.showToast({ title: '刷新失败', icon: 'none' });
            }
        }
        finally {
            if (fromRefresh) {
                wx.stopPullDownRefresh();
                this.setData({ refreshing: false });
            }
        }
    },
    async reloadListFirstPage() {
        const uid = this.data.userIdNum;
        if (!uid || !this.data.profile) {
            return;
        }
        this.setData({ listLoadError: false, listErrorMessage: '', listNeedAuthVerify: false });
        try {
            const res = await (0, user_service_1.getUserPublicContents)(uid, 1, this.data.pageSize);
            if (!res.ok) {
                const msg = (res.message && String(res.message).trim()) || '加载失败';
                this.setData({
                    listLoadError: true,
                    listErrorMessage: msg,
                    listNeedAuthVerify: (0, detail_shared_1.isRealNameRequiredMessage)(msg),
                    list: [],
                    current: 1,
                    finished: true,
                });
                return;
            }
            const { list, hasMore } = res.data;
            this.setData({
                list,
                current: 1,
                finished: !hasMore || list.length < this.data.pageSize,
                listLoadError: false,
                listErrorMessage: '',
                listNeedAuthVerify: false,
            });
        }
        catch {
            this.setData({
                listLoadError: true,
                listErrorMessage: '网络异常，请稍后重试',
                listNeedAuthVerify: false,
                list: [],
                current: 1,
                finished: true,
            });
        }
    },
    async loadMore() {
        if (this._loadMoreLock) {
            return;
        }
        const uid = this.data.userIdNum;
        if (!uid ||
            this.data.loading ||
            this.data.refreshing ||
            this.data.loadingMore ||
            this.data.finished ||
            !this.data.list.length) {
            return;
        }
        this._loadMoreLock = true;
        this.setData({ loadingMore: true, loadMoreError: false });
        try {
            const next = this.data.current + 1;
            const res = await (0, user_service_1.getUserPublicContents)(uid, next, this.data.pageSize);
            if (!res.ok) {
                this.setData({ loadMoreError: true });
                return;
            }
            const { list: nextList, hasMore } = res.data;
            const merged = [...this.data.list];
            const seen = new Set(merged.map((x) => x.contentId));
            for (const c of nextList) {
                if (!seen.has(c.contentId)) {
                    seen.add(c.contentId);
                    merged.push(c);
                }
            }
            this.setData({
                list: merged,
                current: next,
                finished: !hasMore || nextList.length < this.data.pageSize,
            });
        }
        catch {
            this.setData({ loadMoreError: true });
        }
        finally {
            this._loadMoreLock = false;
            this.setData({ loadingMore: false });
        }
    },
});
