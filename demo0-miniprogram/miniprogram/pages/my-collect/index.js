"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
const request_1 = require("../../constants/request");
const route_1 = require("../../constants/route");
const user_content_service_1 = require("../../services/user-content.service");
const detail_shared_1 = require("../../utils/detail-shared");
const feed_error_1 = require("../../utils/feed-error");
const page_refresh_1 = require("../../utils/page-refresh");
const user_profile_nav_1 = require("../../utils/user-profile-nav");
const content_card_list_1 = require("../../utils/content-card-list");
const content_card_navigate_1 = require("../../utils/content-card-navigate");
Page({
    data: {
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
        current: 1,
        pageSize: request_1.DEFAULT_PAGE_SIZE,
        loadMoreError: false,
    },
    _loadMoreLock: false,
    _refreshSnapshot: (0, page_refresh_1.createPageRefreshSnapshot)(),
    onLoad() {
        void this.loadFirstPage();
    },
    onShow() {
        (0, page_refresh_1.handleMineListPageOnShow)(this, () => {
            void this.loadFirstPage();
        });
    },
    onPullDownRefresh() {
        this.setData({ refreshing: true });
        void this.loadFirstPage(true);
    },
    onReachBottom() {
        if (this.data.loading ||
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
        void this.loadFirstPage();
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
    async loadFirstPage(fromRefresh = false) {
        if (!fromRefresh) {
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
        try {
            const res = await (0, user_content_service_1.getMyCollectList)(1, this.data.pageSize);
            if (!res.ok) {
                if (fromRefresh && this.data.list.length) {
                    wx.showToast({ title: '刷新失败', icon: 'none' });
                }
                else if (!fromRefresh || !this.data.list.length) {
                    if ((0, detail_shared_1.isRealNameRequiredMessage)(res.message)) {
                        this.setData({
                            loading: false,
                            errorType: 'server',
                            stateTitle: '需完成实名认证',
                            errorMessage: res.message || '完成实名认证后可查看收藏',
                            stateActionText: '去认证',
                            needAuthVerify: true,
                        });
                    }
                    else {
                        const err = (0, feed_error_1.feedFullScreenError)(res);
                        this.setData({
                            loading: false,
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
                loading: false,
                errorType: '',
                errorMessage: '',
                stateTitle: '',
                stateActionText: '',
                needAuthVerify: false,
            });
        }
        catch {
            if (fromRefresh && this.data.list.length) {
                wx.showToast({ title: '刷新失败', icon: 'none' });
            }
            else {
                this.setData({
                    loading: false,
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
            const res = await (0, user_content_service_1.getMyCollectList)(next, this.data.pageSize);
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
