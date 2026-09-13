"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
const content_1 = require("../../constants/content");
const request_1 = require("../../constants/request");
const route_1 = require("../../constants/route");
const rag_service_1 = require("../../services/rag.service");
const search_service_1 = require("../../services/search.service");
const feed_error_1 = require("../../utils/feed-error");
const feed_switch_1 = require("../../utils/feed-switch");
const content_card_navigate_1 = require("../../utils/content-card-navigate");
const user_profile_nav_1 = require("../../utils/user-profile-nav");
const content_card_list_1 = require("../../utils/content-card-list");
function safeDecodeKeyword(raw) {
    const s = (raw || '').trim();
    if (!s) {
        return '';
    }
    try {
        return decodeURIComponent(s);
    }
    catch {
        return s;
    }
}
function parseFilterFromQuery(q) {
    const raw = q.contentType;
    if (raw === '1' || raw === `${content_1.CONTENT_TYPE_LIFE}`) {
        return content_1.CONTENT_TYPE_LIFE;
    }
    if (raw === '2' || raw === `${content_1.CONTENT_TYPE_PROFESSIONAL}`) {
        return content_1.CONTENT_TYPE_PROFESSIONAL;
    }
    return content_1.CONTENT_TYPE_ALL;
}
function emptyAiSummary(keyword, filter) {
    return {
        keyword,
        filter,
        summary: '',
        sourceCount: 0,
        loading: false,
        errorType: '',
        errorMessage: '',
    };
}
let resultPageToken = 0;
Page({
    data: {
        invalidKeyword: false,
        keyword: '',
        filter: content_1.CONTENT_TYPE_ALL,
        list: [],
        switching: false,
        aiSummary: emptyAiSummary('', content_1.CONTENT_TYPE_ALL),
        loading: true,
        refreshing: false,
        loadingMore: false,
        finished: false,
        errorType: '',
        errorMessage: '',
        stateTitle: '',
        stateActionText: '',
        current: 1,
        pageSize: request_1.DEFAULT_PAGE_SIZE,
        loadMoreError: false,
    },
    _loadMoreLock: false,
    onLoad(query) {
        const keyword = safeDecodeKeyword(query.keyword ?? '');
        const filter = parseFilterFromQuery(query);
        if (!keyword) {
            this.setData({
                invalidKeyword: true,
                loading: false,
                aiSummary: emptyAiSummary('', filter),
            });
            return;
        }
        this.setData({
            invalidKeyword: false,
            keyword,
            filter,
            aiSummary: (0, rag_service_1.aiSummaryLoading)(keyword, filter),
        });
        void this.reloadAll(false, { filter });
    },
    onPullDownRefresh() {
        if (this.data.invalidKeyword) {
            wx.stopPullDownRefresh();
            return;
        }
        this.setData({ refreshing: true });
        void this.reloadAll(true, { filter: this.data.filter });
    },
    onReachBottom() {
        if (this.data.invalidKeyword ||
            this.data.loading ||
            this.data.switching ||
            this.data.refreshing ||
            this.data.loadingMore ||
            this.data.finished ||
            !this.data.list.length) {
            return;
        }
        void this.loadMore();
    },
    onBackToSearch() {
        wx.navigateBack();
    },
    onFilterChange(e) {
        const next = e.detail?.value;
        if (next === undefined || next === this.data.filter) {
            return;
        }
        wx.pageScrollTo({ scrollTop: 0, duration: 0 });
        const hadContent = this.data.list.length > 0;
        this.setData({
            filter: next,
            ...(0, feed_switch_1.beginFeedTabSwitch)(),
            ...(hadContent ? {} : { list: [] }),
        });
        void this.reloadAll(false, { filter: next, tabSwitch: true });
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
    onAiSummaryTap() {
        const { keyword, filter } = this.data;
        if (!keyword) {
            return;
        }
        const k = encodeURIComponent(keyword);
        let url = `${route_1.ROUTES.SEARCH_AI_DETAIL}?keyword=${k}`;
        if (filter === content_1.CONTENT_TYPE_LIFE) {
            url += '&contentType=1';
        }
        else if (filter === content_1.CONTENT_TYPE_PROFESSIONAL) {
            url += '&contentType=2';
        }
        wx.navigateTo({ url });
    },
    onStateAction() {
        void this.reloadAll(false, { filter: this.data.filter });
    },
    onFooterRetry() {
        this.setData({ loadMoreError: false });
        void this.loadMore();
    },
    async reloadAll(fromRefresh = false, opts) {
        const token = ++resultPageToken;
        const filter = opts?.filter ?? this.data.filter;
        const keyword = this.data.keyword;
        const pageSize = this.data.pageSize;
        const wasSwitching = opts?.tabSwitch === true || this.data.switching;
        if (!fromRefresh && !wasSwitching) {
            this.setData({
                loading: true,
                list: [],
                current: 1,
                finished: false,
                errorType: '',
                errorMessage: '',
                stateTitle: '',
                stateActionText: '',
                loadMoreError: false,
                aiSummary: (0, rag_service_1.aiSummaryLoading)(keyword, filter),
            });
        }
        else if (wasSwitching) {
            this.setData({
                current: 1,
                finished: false,
                errorType: '',
                errorMessage: '',
                stateTitle: '',
                stateActionText: '',
                loadMoreError: false,
                aiSummary: (0, rag_service_1.aiSummaryLoading)(keyword, filter),
            });
        }
        else {
            this.setData({
                errorType: '',
                errorMessage: '',
                stateTitle: '',
                stateActionText: '',
                loadMoreError: false,
                aiSummary: (0, rag_service_1.aiSummaryLoading)(keyword, filter),
            });
        }
        void (0, rag_service_1.ragSearchContent)((0, rag_service_1.buildRagSearchRequest)(keyword, filter, true)).then((res) => {
            if (token !== resultPageToken) {
                return;
            }
            if (!res.ok) {
                this.setData({
                    aiSummary: {
                        keyword,
                        filter,
                        summary: '',
                        sourceCount: 0,
                        loading: false,
                        errorType: res.errorType,
                        errorMessage: res.message,
                    },
                });
                return;
            }
            this.setData({
                aiSummary: (0, rag_service_1.mapRagResponseToAiSummary)(res.data, keyword, filter),
            });
        });
        try {
            const res = await (0, search_service_1.searchContent)((0, search_service_1.buildSearchQuery)(keyword, filter, 1, pageSize));
            if (token !== resultPageToken) {
                return;
            }
            if (!res.ok) {
                if ((0, feed_switch_1.feedSwitchFailUsesToast)(fromRefresh, wasSwitching)) {
                    this.setData((0, feed_switch_1.feedSwitchDone)());
                    wx.showToast({ title: fromRefresh ? '刷新失败' : '加载失败', icon: 'none' });
                }
                else {
                    this.setData({
                        ...(0, feed_switch_1.feedSwitchDone)(),
                        ...(0, feed_error_1.feedFullScreenError)(res),
                    });
                }
                return;
            }
            const mapped = (0, search_service_1.mapSearchPageToCards)(res.data);
            const rawList = Array.isArray(res.data.list) ? res.data.list : [];
            if (rawList.length > 0 && mapped.list.length === 0) {
                const invalidRes = {
                    ok: false,
                    errorType: 'invalidData',
                    message: '暂无可展示的有效内容',
                };
                if ((0, feed_switch_1.feedSwitchFailUsesToast)(fromRefresh, wasSwitching)) {
                    this.setData((0, feed_switch_1.feedSwitchDone)());
                    wx.showToast({ title: '暂无有效内容', icon: 'none' });
                }
                else {
                    this.setData({
                        list: [],
                        current: 1,
                        finished: true,
                        ...(0, feed_switch_1.feedSwitchDone)(),
                        ...(0, feed_error_1.feedFullScreenError)(invalidRes),
                    });
                }
                return;
            }
            this.setData({
                list: mapped.list,
                current: 1,
                finished: !mapped.hasMore || mapped.list.length < pageSize,
                ...(0, feed_switch_1.feedSwitchDone)(),
                errorType: '',
                errorMessage: '',
                stateTitle: '',
                stateActionText: '',
            });
        }
        catch {
            if (token !== resultPageToken) {
                return;
            }
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
        if (this.data.invalidKeyword ||
            this.data.loading ||
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
            const { keyword, filter, current, pageSize, list } = this.data;
            const nextCurrent = current + 1;
            const res = await (0, search_service_1.searchContent)((0, search_service_1.buildSearchQuery)(keyword, filter, nextCurrent, pageSize));
            if (!res.ok) {
                this.setData({ loadMoreError: true });
                return;
            }
            const mapped = (0, search_service_1.mapSearchPageToCards)(res.data);
            const merged = list.concat(mapped.list.filter((card) => !list.some((it) => it.contentId === card.contentId)));
            this.setData({
                list: merged,
                current: nextCurrent,
                finished: !mapped.hasMore || mapped.list.length < pageSize,
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
