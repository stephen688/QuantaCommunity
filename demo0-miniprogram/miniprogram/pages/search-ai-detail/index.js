"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
const content_1 = require("../../constants/content");
const rag_service_1 = require("../../services/rag.service");
const feed_error_1 = require("../../utils/feed-error");
const user_profile_nav_1 = require("../../utils/user-profile-nav");
const content_card_list_1 = require("../../utils/content-card-list");
const content_card_navigate_1 = require("../../utils/content-card-navigate");
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
function filterLabel(filter) {
    if (filter === content_1.CONTENT_TYPE_LIFE) {
        return '生活';
    }
    if (filter === content_1.CONTENT_TYPE_PROFESSIONAL) {
        return '专业';
    }
    return '全部';
}
Page({
    data: {
        keyword: '',
        filter: content_1.CONTENT_TYPE_ALL,
        filterLabel: '全部',
        summary: '',
        emptyHint: '',
        sourceList: [],
        loading: true,
        errorType: '',
        errorMessage: '',
        stateTitle: '',
        stateActionText: '',
    },
    onLoad(query) {
        const keyword = safeDecodeKeyword(query.keyword ?? '');
        const filter = parseFilterFromQuery(query);
        if (!keyword) {
            this.setData({
                loading: false,
                ...(0, feed_error_1.feedFullScreenError)({
                    ok: false,
                    errorType: 'invalidData',
                    message: '搜索关键词无效',
                }),
            });
            return;
        }
        this.setData({
            keyword,
            filter,
            filterLabel: filterLabel(filter),
        });
        void this.load();
    },
    onStateAction() {
        void this.load();
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
            sourceList: (0, content_card_list_1.patchContentCardInList)(this.data.sourceList, next),
        });
    },
    async load() {
        const { keyword, filter } = this.data;
        this.setData({
            loading: true,
            errorType: '',
            errorMessage: '',
            stateTitle: '',
            stateActionText: '',
            summary: '',
            emptyHint: '',
            sourceList: [],
        });
        try {
            const res = await (0, rag_service_1.ragSearchContent)((0, rag_service_1.buildRagSearchRequest)(keyword, filter, true));
            if (!res.ok) {
                this.setData({
                    loading: false,
                    ...(0, feed_error_1.feedFullScreenError)(res),
                });
                return;
            }
            const ai = (0, rag_service_1.mapRagResponseToAiSummary)(res.data, keyword, filter);
            const sourceList = (0, rag_service_1.mapRagResponseToCards)(res.data);
            const summary = (ai.summary || '').trim();
            this.setData({
                loading: false,
                summary,
                sourceList,
                emptyHint: ai.errorMessage || '',
                errorType: '',
                errorMessage: '',
                stateTitle: '',
                stateActionText: '',
            });
        }
        catch {
            this.setData({
                loading: false,
                ...(0, feed_error_1.feedFullScreenError)({
                    ok: false,
                    errorType: 'network',
                    message: '请求异常，请稍后重试',
                }),
            });
        }
    },
});
