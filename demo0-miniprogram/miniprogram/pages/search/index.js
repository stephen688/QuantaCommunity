"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
const content_1 = require("../../constants/content");
const route_1 = require("../../constants/route");
const content_service_1 = require("../../services/content.service");
const search_service_1 = require("../../services/search.service");
const feed_error_1 = require("../../utils/feed-error");
const user_profile_nav_1 = require("../../utils/user-profile-nav");
function buildSearchResultUrl(keyword) {
    const k = encodeURIComponent(keyword);
    return `${route_1.ROUTES.SEARCH_RESULT}?keyword=${k}`;
}
function buildDetailUrlByType(contentId, contentType) {
    if (contentType === content_1.CONTENT_TYPE_LIFE) {
        return `${route_1.ROUTES.DETAIL_LIFE}?contentId=${contentId}`;
    }
    if (contentType === content_1.CONTENT_TYPE_PROFESSIONAL) {
        return `${route_1.ROUTES.DETAIL_PRO}?contentId=${contentId}`;
    }
    return '';
}
Page({
    data: {
        inputValue: '',
        historyKeywords: [],
        historyLoading: true,
        historyErrorType: '',
        historyStateTitle: '',
        historyErrorMessage: '',
        historyStateActionText: '',
        hotKeywords: [],
        hotQuestions: [],
        lastHotKeyword: '',
        hotAlumni: [],
        showHotRecommend: false,
        hotHint: '',
        hotQuestionNavigating: false,
    },
    onLoad() {
        void this.loadHistory();
        void this.loadHotRecommend();
    },
    onPullDownRefresh() {
        void Promise.all([this.loadHistory(true), this.loadHotRecommend()]).finally(() => {
            wx.stopPullDownRefresh();
        });
    },
    onSearchInput(e) {
        this.setData({ inputValue: e.detail?.value ?? '' });
    },
    onSearchConfirm(e) {
        const keyword = (e.detail?.value ?? this.data.inputValue).trim();
        if (!keyword) {
            wx.showToast({ title: '请输入搜索关键词', icon: 'none' });
            return;
        }
        wx.navigateTo({ url: buildSearchResultUrl(keyword) });
    },
    onSearchClear() {
        this.setData({ inputValue: '' });
    },
    onSearchCancel() {
        const pages = getCurrentPages();
        if (pages.length > 1) {
            wx.navigateBack();
        }
    },
    onHistoryTap(e) {
        const keyword = String(e.currentTarget.dataset.keyword || '').trim();
        if (!keyword) {
            return;
        }
        wx.navigateTo({ url: buildSearchResultUrl(keyword) });
    },
    onHotKeywordTap(e) {
        const keyword = String(e.currentTarget.dataset.keyword || '').trim();
        if (!keyword) {
            return;
        }
        this.setData({ lastHotKeyword: keyword });
        wx.navigateTo({ url: buildSearchResultUrl(keyword) });
    },
    async onHotQuestionTap(e) {
        if (this.data.hotQuestionNavigating) {
            return;
        }
        const contentId = Number(e.currentTarget.dataset.contentId);
        if (!Number.isFinite(contentId) || contentId <= 0) {
            return;
        }
        let contentType = Number(e.currentTarget.dataset.contentType);
        if (contentType !== content_1.CONTENT_TYPE_LIFE &&
            contentType !== content_1.CONTENT_TYPE_PROFESSIONAL) {
            this.setData({ hotQuestionNavigating: true });
            wx.showLoading({ title: '加载中', mask: true });
            const res = await (0, content_service_1.getContentDetail)(contentId);
            wx.hideLoading();
            this.setData({ hotQuestionNavigating: false });
            if (!res.ok) {
                wx.showToast({ title: res.message || '无法打开详情', icon: 'none' });
                return;
            }
            contentType = Number(res.data?.contentType);
        }
        const url = buildDetailUrlByType(contentId, contentType);
        if (!url) {
            wx.showToast({ title: '内容类型异常', icon: 'none' });
            return;
        }
        wx.navigateTo({ url });
    },
    onHotAlumniTap(e) {
        (0, user_profile_nav_1.navigateToUserProfile)(e.currentTarget.dataset.userId);
    },
    onHistoryStateAction() {
        void this.loadHistory(false);
    },
    async onClearHistory() {
        const keywords = this.data.historyKeywords;
        if (!keywords.length) {
            return;
        }
        const confirmRes = await wx.showModal({
            title: '清空历史',
            content: '确认清空全部搜索历史吗？',
            confirmText: '清空',
        });
        if (!confirmRes.confirm) {
            return;
        }
        const res = await (0, search_service_1.clearSearchHistory)();
        if (!res.ok) {
            wx.showToast({ title: res.message || '清空失败', icon: 'none' });
            return;
        }
        this.setData({ historyKeywords: [] });
    },
    async loadHotRecommend() {
        try {
            const res = await (0, search_service_1.getSearchTrending)();
            if (!res.ok) {
                this.setData({
                    showHotRecommend: false,
                    hotKeywords: [],
                    hotQuestions: [],
                    hotAlumni: [],
                    hotHint: '',
                });
                return;
            }
            const data = res.data;
            const hasContent = (data.keywords?.length ?? 0) > 0 ||
                (data.questions?.length ?? 0) > 0 ||
                (data.alumni?.length ?? 0) > 0;
            this.setData({
                hotKeywords: data.keywords ?? [],
                hotQuestions: data.questions ?? [],
                hotAlumni: data.alumni ?? [],
                showHotRecommend: hasContent,
                hotHint: '',
            });
        }
        catch {
            this.setData({
                showHotRecommend: false,
                hotKeywords: [],
                hotQuestions: [],
                hotAlumni: [],
                hotHint: '',
            });
        }
    },
    async loadHistory(fromPullDown = false) {
        if (!fromPullDown) {
            this.setData({
                historyLoading: true,
                historyErrorType: '',
                historyStateTitle: '',
                historyErrorMessage: '',
                historyStateActionText: '',
            });
        }
        try {
            const res = await (0, search_service_1.getSearchHistoryKeywords)();
            if (!res.ok) {
                if (fromPullDown) {
                    wx.showToast({ title: res.message || '刷新失败', icon: 'none' });
                }
                else {
                    const err = (0, feed_error_1.feedFullScreenError)(res);
                    this.setData({
                        historyLoading: false,
                        historyKeywords: [],
                        historyErrorType: err.errorType,
                        historyStateTitle: err.stateTitle,
                        historyErrorMessage: err.errorMessage,
                        historyStateActionText: err.stateActionText,
                    });
                }
                return;
            }
            const list = Array.isArray(res.data)
                ? res.data.map((item) => String(item || '').trim()).filter(Boolean)
                : [];
            this.setData({
                historyKeywords: list,
                historyLoading: false,
                historyErrorType: '',
                historyStateTitle: '',
                historyErrorMessage: '',
                historyStateActionText: '',
            });
        }
        catch {
            if (fromPullDown) {
                wx.showToast({ title: '刷新失败', icon: 'none' });
            }
            else {
                const err = (0, feed_error_1.feedFullScreenError)({
                    ok: false,
                    errorType: 'network',
                    message: '请求异常，请稍后重试',
                });
                this.setData({
                    historyLoading: false,
                    historyKeywords: [],
                    historyErrorType: err.errorType,
                    historyStateTitle: err.stateTitle,
                    historyErrorMessage: err.errorMessage,
                    historyStateActionText: err.stateActionText,
                });
            }
        }
    },
});
