"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
const request_1 = require("../../constants/request");
const route_1 = require("../../constants/route");
const user_content_service_1 = require("../../services/user-content.service");
const detail_shared_1 = require("../../utils/detail-shared");
const feed_error_1 = require("../../utils/feed-error");
const feed_switch_1 = require("../../utils/feed-switch");
const page_refresh_1 = require("../../utils/page-refresh");
const user_profile_nav_1 = require("../../utils/user-profile-nav");
const content_card_list_1 = require("../../utils/content-card-list");
const content_card_navigate_1 = require("../../utils/content-card-navigate");
function auditParamFromTab(tab) {
    if (tab === 'pending') {
        return 'PENDING';
    }
    if (tab === 'approved') {
        return 'APPROVED';
    }
    if (tab === 'rejected') {
        return 'REJECTED';
    }
    return '';
}
Page({
    data: {
        auditTab: 'all',
        list: [],
        switching: false,
        loading: true,
        refreshing: false,
        loadingMore: false,
        finished: false,
        errorType: '',
        errorMessage: '',
        stateTitle: '',
        stateActionText: '',
        needAuthVerify: false,
        current: 1,
        pageSize: request_1.DEFAULT_PAGE_SIZE,
        loadMoreError: false,
    },
    _loadMoreLock: false,
    _refreshSnapshot: (0, page_refresh_1.createPageRefreshSnapshot)(),
    onLoad(query) {
        const auditRaw = query?.audit;
        if (auditRaw === '0' || auditRaw === 'pending') {
            this.setData({ auditTab: 'pending' });
        }
        void this.loadFirstPage();
    },
    onShow() {
        (0, page_refresh_1.handleMineListPageOnShow)(this, () => {
            void this.loadFirstPage();
        });
    },
    onPullDownRefresh() {
        this.setData({ refreshing: true });
        void this.loadFirstPage({ fromRefresh: true });
    },
    onReachBottom() {
        if (this.data.loading ||
            this.data.switching ||
            this.data.refreshing ||
            this.data.loadingMore ||
            this.data.finished ||
            !this.data.list.length) {
            return;
        }
        void this.loadMore();
    },
    onAuditTap(e) {
        const tab = e.currentTarget.dataset.tab;
        if (!tab || tab === this.data.auditTab) {
            return;
        }
        wx.pageScrollTo({ scrollTop: 0, duration: 0 });
        const hadContent = this.data.list.length > 0;
        this.setData({
            auditTab: tab,
            ...(0, feed_switch_1.beginFeedTabSwitch)(),
            ...(hadContent ? {} : { list: [] }),
        });
        void this.loadFirstPage({ tabSwitch: true });
    },
    onStateAction() {
        if (this.data.needAuthVerify) {
            wx.navigateTo({ url: `${route_1.ROUTES.PROFILE_EDIT}?intent=verify` });
            return;
        }
        void this.loadFirstPage();
    },
    onEmptyPublish() {
        wx.navigateTo({ url: route_1.ROUTES.PUBLISH });
    },
    onFooterRetry() {
        this.setData({ loadMoreError: false });
        void this.loadMore();
    },
    onCardTap(e) {
        (0, content_card_navigate_1.navigateFromContentCardEvent)(e);
    },
    onCardAuthorTap(e) {
        (0, user_profile_nav_1.navigateToUserProfileFromEvent)(e);
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
    async loadFirstPage(opts) {
        const fromRefresh = opts?.fromRefresh === true;
        const wasSwitching = opts?.tabSwitch === true || this.data.switching;
        if (!fromRefresh) {
            if (wasSwitching) {
                this.setData({
                    current: 1,
                    finished: false,
                    errorType: '',
                    errorMessage: '',
                    stateTitle: '',
                    stateActionText: '',
                    needAuthVerify: false,
                    loadMoreError: false,
                });
            }
            else {
                this.setData({
                    loading: true,
                    list: [],
                    current: 1,
                    finished: false,
                    errorType: '',
                    errorMessage: '',
                    stateTitle: '',
                    stateActionText: '',
                    needAuthVerify: false,
                    loadMoreError: false,
                });
            }
        }
        else {
            this.setData({
                errorType: '',
                errorMessage: '',
                stateTitle: '',
                stateActionText: '',
                needAuthVerify: false,
                loadMoreError: false,
            });
        }
        const audit = auditParamFromTab(this.data.auditTab);
        try {
            const res = await (0, user_content_service_1.getMyContentList)(1, this.data.pageSize, audit);
            if (!res.ok) {
                if ((0, feed_switch_1.feedSwitchFailUsesToast)(fromRefresh, wasSwitching)) {
                    this.setData((0, feed_switch_1.feedSwitchDone)());
                    wx.showToast({ title: fromRefresh ? '刷新失败' : '加载失败', icon: 'none' });
                }
                else if (!fromRefresh || !this.data.list.length) {
                    if ((0, detail_shared_1.isRealNameRequiredMessage)(res.message)) {
                        this.setData({
                            ...(0, feed_switch_1.feedSwitchDone)(),
                            errorType: 'server',
                            stateTitle: '需完成实名认证',
                            errorMessage: res.message || '请先完成实名认证',
                            stateActionText: '去认证',
                            needAuthVerify: true,
                        });
                    }
                    else {
                        const err = (0, feed_error_1.feedFullScreenError)(res);
                        this.setData({
                            ...(0, feed_switch_1.feedSwitchDone)(),
                            errorType: err.errorType,
                            stateTitle: err.stateTitle,
                            errorMessage: err.errorMessage,
                            stateActionText: err.stateActionText,
                            needAuthVerify: false,
                        });
                    }
                }
                return;
            }
            const { list, hasMore } = res.data;
            this.setData({
                list,
                current: 1,
                finished: !hasMore || list.length < this.data.pageSize,
                ...(0, feed_switch_1.feedSwitchDone)(),
                errorType: '',
                errorMessage: '',
                stateTitle: '',
                stateActionText: '',
                needAuthVerify: false,
            });
        }
        catch {
            if ((0, feed_switch_1.feedSwitchFailUsesToast)(fromRefresh, wasSwitching)) {
                this.setData((0, feed_switch_1.feedSwitchDone)());
                wx.showToast({ title: fromRefresh ? '刷新失败' : '加载失败', icon: 'none' });
            }
            else {
                this.setData({
                    ...(0, feed_switch_1.feedSwitchDone)(),
                    ...(0, feed_error_1.feedFullScreenError)({
                        ok: false,
                        errorType: 'network',
                        message: '请求异常，请稍后重试',
                    }),
                    needAuthVerify: false,
                });
            }
        }
        finally {
            if (fromRefresh) {
                wx.stopPullDownRefresh();
                this.setData({ refreshing: false });
            }
        }
    },
    async loadMore() {
        if (this._loadMoreLock) {
            return;
        }
        if (this.data.loading ||
            this.data.switching ||
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
            const audit = auditParamFromTab(this.data.auditTab);
            const res = await (0, user_content_service_1.getMyContentList)(next, this.data.pageSize, audit);
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
