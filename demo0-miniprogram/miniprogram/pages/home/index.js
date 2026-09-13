"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
const content_1 = require("../../constants/content");
const request_1 = require("../../constants/request");
const route_1 = require("../../constants/route");
const content_service_1 = require("../../services/content.service");
const feed_error_1 = require("../../utils/feed-error");
const feed_switch_1 = require("../../utils/feed-switch");
const pagination_1 = require("../../utils/pagination");
const auth_service_1 = require("../../services/auth.service");
const tab_bar_1 = require("../../utils/tab-bar");
const user_profile_nav_1 = require("../../utils/user-profile-nav");
const content_card_list_1 = require("../../utils/content-card-list");
const content_card_navigate_1 = require("../../utils/content-card-navigate");
const feed_timer_1 = require("../../utils/feed-timer");
function isCardModel(item) {
    return Boolean(item);
}
function emptyCopy(filter, scene = 'latest') {
    const isHot = scene === 'hot';
    if (filter === content_1.CONTENT_TYPE_LIFE) {
        return {
            title: isHot ? '暂无热门生活内容' : '暂无生活内容',
            desc: '切换分区或下拉刷新试试',
        };
    }
    if (filter === content_1.CONTENT_TYPE_PROFESSIONAL) {
        return {
            title: isHot ? '暂无热门专业内容' : '暂无专业内容',
            desc: '切换分区或下拉刷新试试',
        };
    }
    return {
        title: isHot ? '暂无热门推荐' : '暂无推荐',
        desc: isHot ? '切换分区或下拉刷新试试' : '下拉刷新或去发布一条内容',
    };
}
/** 防止快速切换分区时，较早发出的推荐请求覆盖较新结果 */
let homeFeedRequestToken = 0;
Page({
    data: {
        filter: content_1.CONTENT_TYPE_ALL,
        /** 推荐场景：latest 默认 / hot 热度（对应 GET /content/recommend?scene=） */
        scene: 'latest',
        feedSceneTabs: [
            { key: 'latest', label: '默认' },
            { key: 'hot', label: '热度' },
        ],
        categoryTabs: [
            { key: content_1.CONTENT_TYPE_ALL, label: '全部', icon: 'all' },
            { key: content_1.CONTENT_TYPE_LIFE, label: '生活', icon: 'life' },
            { key: content_1.CONTENT_TYPE_PROFESSIONAL, label: '专业', icon: 'pro' },
        ],
        list: [],
        /** 切换分类/热度时保留旧列表，仅弱化展示 + 顶栏细条加载 */
        switching: false,
        loading: true,
        refreshing: false,
        loadingMore: false,
        finished: false,
        errorType: '',
        errorMessage: '',
        stateTitle: '',
        stateActionText: '',
        emptyTitle: emptyCopy(content_1.CONTENT_TYPE_ALL, 'latest').title,
        emptyDesc: emptyCopy(content_1.CONTENT_TYPE_ALL, 'latest').desc,
        lastScore: undefined,
        offset: 0,
        pageSize: request_1.DEFAULT_PAGE_SIZE,
        loadMoreError: false,
    },
    _loadMoreLock: false,
    onLoad() {
        void this.loadFirstPage();
    },
    onShow() {
        this.syncTabBarState();
        void (0, tab_bar_1.refreshCustomTabBarUnread)(this);
        (0, feed_timer_1.startFeedTimer)(() => this.silentRefresh());
        if ((0, auth_service_1.isLoggedIn)() &&
            !this.data.loading &&
            !this.data.switching &&
            !this.data.refreshing &&
            this.data.list.length === 0 &&
            !this.data.errorType) {
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
    resetFeedAndReload(patch) {
        homeFeedRequestToken += 1;
        const filter = patch.filter ?? this.data.filter;
        const scene = patch.scene ?? this.data.scene;
        const copy = emptyCopy(filter, scene);
        const hadContent = this.data.list.length > 0;
        this.setData({
            ...patch,
            filter,
            scene,
            finished: false,
            lastScore: undefined,
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
        });
        wx.pageScrollTo({ scrollTop: 0, duration: 0 });
        void this.loadFirstPage({ tabSwitch: true });
    },
    onHomeCategoryTap(e) {
        const next = e.currentTarget.dataset.key;
        if (next === undefined || next === this.data.filter) {
            return;
        }
        this.resetFeedAndReload({ filter: next });
    },
    onHomeSceneTap(e) {
        const next = e.currentTarget.dataset.key;
        if (next === undefined || next === this.data.scene) {
            return;
        }
        this.resetFeedAndReload({ scene: next });
    },
    onSearchTap() {
        wx.navigateTo({ url: route_1.ROUTES.SEARCH });
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
        wx.navigateTo({ url: route_1.ROUTES.PUBLISH });
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
            const { filter, pageSize, scene } = this.data;
            const res = await (0, content_service_1.getRecommendFeed)((0, content_service_1.buildRecommendQuery)(filter, undefined, 0, pageSize, scene));
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
                lastScore: page.nextLastScore,
                offset: page.nextOffset,
                finished,
            });
        }
        catch {
            // silent refresh failure is intentionally ignored
        }
    },
    syncTabBarState() {
        (0, tab_bar_1.syncCustomTabBarSelected)(this, 0);
    },
    async loadFirstPage(opts) {
        const wasRefreshing = this.data.refreshing;
        const wasSwitching = opts?.tabSwitch === true || this.data.switching;
        const token = ++homeFeedRequestToken;
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
            const { filter, pageSize, scene } = this.data;
            const res = await (0, content_service_1.getRecommendFeed)((0, content_service_1.buildRecommendQuery)(filter, undefined, 0, pageSize, scene));
            if (token !== homeFeedRequestToken) {
                return;
            }
            if (!res.ok) {
                if (res.errorType === 'unauthorized' && !wasRefreshing) {
                    this.setData({
                        ...(0, feed_switch_1.feedSwitchDone)(),
                        loading: false,
                        list: [],
                        ...(0, feed_error_1.feedFullScreenError)(res),
                    });
                    return;
                }
                if (wasRefreshing) {
                    wx.showToast({ title: '刷新失败', icon: 'none' });
                }
                else if ((0, feed_switch_1.feedSwitchFailUsesToast)(wasRefreshing, wasSwitching)) {
                    this.setData((0, feed_switch_1.feedSwitchDone)());
                    wx.showToast({ title: '加载失败', icon: 'none' });
                }
                else {
                    this.setData({
                        ...(0, feed_switch_1.feedSwitchDone)(),
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
                    lastScore: undefined,
                    offset: 0,
                    loadMoreError: false,
                    ...(0, feed_error_1.feedFullScreenError)({
                        ok: false,
                        errorType: 'invalidData',
                        message: '暂无可展示的有效内容',
                    }),
                });
                return;
            }
            const finished = (0, pagination_1.resolveFinished)(cards, pageSize, page.hasMore);
            const copy = emptyCopy(filter, this.data.scene);
            this.setData({
                list: cards,
                lastScore: page.nextLastScore,
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
            });
        }
        catch {
            if (token !== homeFeedRequestToken) {
                return;
            }
            if (wasRefreshing) {
                wx.showToast({ title: '刷新失败', icon: 'none' });
            }
            else if ((0, feed_switch_1.feedSwitchFailUsesToast)(wasRefreshing, wasSwitching)) {
                this.setData((0, feed_switch_1.feedSwitchDone)());
                wx.showToast({ title: '加载失败', icon: 'none' });
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
            const { filter, scene, pageSize, lastScore, offset, list } = this.data;
            const res = await (0, content_service_1.getRecommendFeed)((0, content_service_1.buildRecommendQuery)(filter, lastScore, offset, pageSize, scene));
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
                lastScore: page.nextLastScore,
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
