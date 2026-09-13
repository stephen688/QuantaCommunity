"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
const content_1 = require("../../constants/content");
const request_1 = require("../../constants/request");
const route_1 = require("../../constants/route");
const content_service_1 = require("../../services/content.service");
const follow_service_1 = require("../../services/follow.service");
const feed_error_1 = require("../../utils/feed-error");
const feed_switch_1 = require("../../utils/feed-switch");
const pagination_1 = require("../../utils/pagination");
const tab_bar_1 = require("../../utils/tab-bar");
const follow_feed_bus_1 = require("../../utils/follow-feed-bus");
const content_card_navigate_1 = require("../../utils/content-card-navigate");
const user_profile_nav_1 = require("../../utils/user-profile-nav");
const content_card_list_1 = require("../../utils/content-card-list");
const feed_timer_1 = require("../../utils/feed-timer");
function isCardModel(item) {
    return Boolean(item);
}
function emptyCopy(filter) {
    if (filter === content_1.CONTENT_TYPE_LIFE) {
        return {
            title: '关注的人还没有生活内容',
            desc: '切换为全部或下拉刷新试试',
            action: '查看全部',
            reason: 'noContentInFilter',
        };
    }
    if (filter === content_1.CONTENT_TYPE_PROFESSIONAL) {
        return {
            title: '关注的人还没有专业问题',
            desc: '切换为全部或下拉刷新试试',
            action: '查看全部',
            reason: 'noContentInFilter',
        };
    }
    return {
        title: '还没有关注动态',
        desc: '去发现页看看推荐内容',
        action: '去首页看看',
        reason: 'noFollow',
    };
}
/** 防止快速切换分区时，较早发出的关注流请求覆盖较新结果 */
let followFeedRequestToken = 0;
Page({
    data: {
        filter: content_1.CONTENT_TYPE_ALL,
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
        emptyTitle: '',
        emptyDesc: '',
        emptyActionText: '',
        emptyReason: '',
        lastId: undefined,
        offset: 0,
        pageSize: request_1.DEFAULT_PAGE_SIZE,
        loadMoreError: false,
    },
    _loadMoreLock: false,
    onLoad() {
        const copy = emptyCopy(content_1.CONTENT_TYPE_ALL);
        this.setData({
            emptyTitle: copy.title,
            emptyDesc: copy.desc,
            emptyActionText: copy.action,
            emptyReason: copy.reason,
        });
        void this.loadFirstPage();
    },
    onShow() {
        this.syncTabBarState();
        void (0, tab_bar_1.refreshCustomTabBarUnread)(this);
        (0, feed_timer_1.startFeedTimer)(() => this.silentRefresh());
        if ((0, follow_feed_bus_1.consumeFollowFeedStale)()) {
            void this.loadFirstPage();
        }
    },
    onHide() {
        (0, feed_timer_1.stopFeedTimer)();
    },
    onUnload() {
        (0, feed_timer_1.stopFeedTimer)();
    },
    onPullDownRefresh() {
        this.setData({ refreshing: true });
        void this.loadFirstPage();
    },
    onReachBottom() {
        if (this.data.loading ||
            this.data.switching ||
            this.data.loadingMore ||
            this.data.finished ||
            this.data.refreshing) {
            return;
        }
        if (!this.data.list.length) {
            return;
        }
        void this.loadMore();
    },
    onFilterChange(e) {
        const next = e.detail?.value;
        if (next === undefined || next === this.data.filter) {
            return;
        }
        const copy = emptyCopy(next);
        const hadContent = this.data.list.length > 0;
        this.setData({
            filter: next,
            finished: false,
            lastId: undefined,
            offset: 0,
            errorType: '',
            errorMessage: '',
            stateTitle: '',
            stateActionText: '',
            loadMoreError: false,
            ...(0, feed_switch_1.beginFeedTabSwitch)(),
            ...(hadContent ? {} : { list: [] }),
            emptyTitle: copy.title,
            emptyDesc: copy.desc,
            emptyActionText: copy.action,
            emptyReason: copy.reason,
        });
        wx.pageScrollTo({ scrollTop: 0, duration: 0 });
        void this.loadFirstPage({ tabSwitch: true });
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
    onStateAction() {
        void this.loadFirstPage();
    },
    onEmptyAction() {
        const { emptyReason } = this.data;
        if (emptyReason === 'noFollow') {
            wx.switchTab({ url: route_1.ROUTES.HOME });
            return;
        }
        if (emptyReason === 'noContentInFilter') {
            const copy = emptyCopy(content_1.CONTENT_TYPE_ALL);
            const hadContent = this.data.list.length > 0;
            this.setData({
                filter: content_1.CONTENT_TYPE_ALL,
                finished: false,
                lastId: undefined,
                offset: 0,
                errorType: '',
                errorMessage: '',
                loadMoreError: false,
                ...(0, feed_switch_1.beginFeedTabSwitch)(),
                ...(hadContent ? {} : { list: [] }),
                emptyTitle: copy.title,
                emptyDesc: copy.desc,
                emptyActionText: copy.action,
                emptyReason: copy.reason,
            });
            wx.pageScrollTo({ scrollTop: 0, duration: 0 });
            void this.loadFirstPage({ tabSwitch: true });
        }
    },
    onRetryLoadMore() {
        this.setData({ loadMoreError: false });
        void this.loadMore();
    },
    async silentRefresh() {
        if (this.data.loading ||
            this.data.switching ||
            this.data.refreshing ||
            this.data.loadingMore ||
            !this.data.list.length) {
            return;
        }
        try {
            const { filter, pageSize } = this.data;
            const res = await (0, follow_service_1.getFollowFeed)((0, follow_service_1.buildFollowFeedQuery)(filter, undefined, 0, pageSize));
            if (!res.ok) {
                return;
            }
            const page = res.data;
            const rawList = page.list;
            const cards = rawList.map(content_service_1.mapContentVOToCard).filter(isCardModel);
            if (!cards.length) {
                return;
            }
            const finished = (0, pagination_1.resolveFinished)(cards, pageSize, page.hasMore);
            this.setData({
                list: cards,
                lastId: page.nextLastId,
                offset: page.nextOffset,
                finished,
            });
        }
        catch {
            // silent refresh failure is intentionally ignored
        }
    },
    syncTabBarState() {
        (0, tab_bar_1.syncCustomTabBarSelected)(this, 1);
    },
    async loadFirstPage(opts) {
        const wasRefreshing = this.data.refreshing;
        const wasSwitching = opts?.tabSwitch === true || this.data.switching;
        const token = ++followFeedRequestToken;
        if (!wasRefreshing) {
            this.setData({
                loading: (0, feed_switch_1.feedInitialLoading)(this.data.list.length, wasRefreshing, wasSwitching),
                errorType: '',
                errorMessage: '',
                loadMoreError: false,
            });
        }
        else {
            this.setData({ errorType: '', errorMessage: '', loadMoreError: false });
        }
        try {
            const { filter, pageSize } = this.data;
            const res = await (0, follow_service_1.getFollowFeed)((0, follow_service_1.buildFollowFeedQuery)(filter, undefined, 0, pageSize));
            if (token !== followFeedRequestToken) {
                return;
            }
            if (!res.ok) {
                if ((0, feed_switch_1.feedSwitchFailUsesToast)(wasRefreshing, wasSwitching)) {
                    this.setData((0, feed_switch_1.feedSwitchDone)());
                    wx.showToast({ title: wasRefreshing ? '刷新失败' : '加载失败', icon: 'none' });
                }
                else {
                    this.setData({
                        ...(0, feed_switch_1.feedSwitchDone)(),
                        emptyReason: '',
                        ...(0, feed_error_1.feedFullScreenError)(res),
                    });
                }
                return;
            }
            const page = res.data;
            const rawList = page.list;
            const cards = rawList.map(content_service_1.mapContentVOToCard).filter(isCardModel);
            if (rawList.length > 0 && cards.length === 0) {
                if ((0, feed_switch_1.feedSwitchFailUsesToast)(wasRefreshing, wasSwitching)) {
                    this.setData((0, feed_switch_1.feedSwitchDone)());
                    wx.showToast({ title: '暂无有效内容', icon: 'none' });
                    return;
                }
                this.setData({
                    ...(0, feed_switch_1.feedSwitchDone)(),
                    list: [],
                    finished: true,
                    lastId: undefined,
                    offset: 0,
                    loadMoreError: false,
                    emptyReason: '',
                    ...(0, feed_error_1.feedFullScreenError)({
                        ok: false,
                        errorType: 'invalidData',
                        message: '暂无可展示的有效内容',
                    }),
                });
                return;
            }
            const finished = (0, pagination_1.resolveFinished)(cards, pageSize, page.hasMore);
            const copy = emptyCopy(filter);
            this.setData({
                list: cards,
                lastId: page.nextLastId,
                offset: page.nextOffset,
                finished,
                ...(0, feed_switch_1.feedSwitchDone)(),
                errorType: '',
                errorMessage: '',
                stateTitle: '',
                stateActionText: '',
                loadMoreError: false,
                emptyTitle: copy.title,
                emptyDesc: copy.desc,
                emptyActionText: copy.action,
                emptyReason: cards.length ? '' : copy.reason,
            });
        }
        catch {
            if (token !== followFeedRequestToken) {
                return;
            }
            if ((0, feed_switch_1.feedSwitchFailUsesToast)(wasRefreshing, wasSwitching)) {
                this.setData((0, feed_switch_1.feedSwitchDone)());
                wx.showToast({ title: wasRefreshing ? '刷新失败' : '加载失败', icon: 'none' });
            }
            else {
                this.setData({
                    ...(0, feed_switch_1.feedSwitchDone)(),
                    emptyReason: '',
                    ...(0, feed_error_1.feedFullScreenError)({
                        ok: false,
                        errorType: 'network',
                        message: '请求异常，请稍后重试',
                    }),
                });
            }
        }
        finally {
            if (wasRefreshing) {
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
            this.data.loadingMore ||
            this.data.finished ||
            this.data.refreshing ||
            !this.data.list.length) {
            return;
        }
        this._loadMoreLock = true;
        this.setData({ loadingMore: true, loadMoreError: false });
        try {
            const { filter, pageSize, lastId, offset, list } = this.data;
            const res = await (0, follow_service_1.getFollowFeed)((0, follow_service_1.buildFollowFeedQuery)(filter, lastId, offset, pageSize));
            if (!res.ok) {
                this.setData({ loadMoreError: true });
                return;
            }
            const page = res.data;
            const cards = page.list.map(content_service_1.mapContentVOToCard).filter(isCardModel);
            const merged = (0, pagination_1.mergeFeedList)(list, cards);
            const finished = (0, pagination_1.resolveFinished)(cards, pageSize, page.hasMore);
            this.setData({
                list: merged,
                lastId: page.nextLastId,
                offset: page.nextOffset,
                finished,
            });
        }
        catch {
            this.setData({ loadMoreError: true });
        }
        finally {
            this.setData({ loadingMore: false });
            this._loadMoreLock = false;
        }
    },
});
