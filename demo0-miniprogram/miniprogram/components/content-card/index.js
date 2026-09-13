"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
const content_1 = require("../../constants/content");
const route_1 = require("../../constants/route");
const auth_service_1 = require("../../services/auth.service");
const interaction_service_1 = require("../../services/interaction.service");
const format_1 = require("../../utils/format");
const image_1 = require("../../utils/image");
Component({
    properties: {
        item: {
            type: Object,
            value: {},
        },
        showAuthor: {
            type: Boolean,
            value: true,
        },
        /** 标题下方展示作者（与 showAuthor 互斥：有 showAuthor 时不展示标题下作者） */
        authorUnderTitle: {
            type: Boolean,
            value: false,
        },
        /** 我的发布等：展示审核状态胶囊 */
        showAuditBadge: {
            type: Boolean,
            value: false,
        },
        /** 底部赞/藏/评是否可点击操作 */
        interactiveStats: {
            type: Boolean,
            value: true,
        },
    },
    data: {
        isLife: false,
        isPro: false,
        imageList: [],
        hasSingleImage: false,
        hasMultiImages: false,
        summary: '',
        likeText: '0',
        collectText: '0',
        commentText: '0',
        answerText: '',
        showAnswer: false,
        metaTimeText: '',
        auditLabel: '',
        auditTone: '',
        liked: false,
        collected: false,
        likeLoading: false,
        collectLoading: false,
    },
    observers: {
        item() {
            this.applyItem();
        },
    },
    lifetimes: {
        attached() {
            this.applyItem();
        },
    },
    methods: {
        applyItem() {
            const raw = this.data.item;
            const item = raw && typeof raw === 'object' ? raw : {};
            const ct = item.contentType;
            const isLife = ct === content_1.CONTENT_TYPE_LIFE;
            const isPro = ct === content_1.CONTENT_TYPE_PROFESSIONAL;
            const imageList = (0, image_1.normalizeImages)(item.images).slice(0, 5);
            const hasSingleImage = imageList.length === 1;
            const hasMultiImages = imageList.length > 1;
            const summary = (0, format_1.formatSummary)(item.content || item.title, isPro ? 100 : 72);
            const answerText = item.answerCount !== undefined && item.answerCount !== null
                ? `${(0, format_1.formatCompactNumber)(item.answerCount)} 回答`
                : '';
            const metaTimeText = (0, format_1.formatRelativeTime)(item.createTime);
            const audit = this.data.showAuditBadge
                ? resolveAuditBadge(item.auditStatus)
                : { label: '', tone: '' };
            this.setData({
                isLife,
                isPro,
                imageList,
                hasSingleImage,
                hasMultiImages,
                summary,
                likeText: (0, format_1.formatCompactNumber)(item.likeCount),
                collectText: (0, format_1.formatCompactNumber)(item.collectCount),
                commentText: (0, format_1.formatCompactNumber)(item.commentCount),
                answerText,
                showAnswer: isPro && Boolean(answerText),
                metaTimeText,
                auditLabel: audit.label,
                auditTone: audit.tone,
                liked: item.liked === true,
                collected: item.collected === true,
            });
        },
        onTap() {
            const item = this.properties.item;
            this.triggerEvent('cardtap', { item });
        },
        onAuthorRowTap(e) {
            this.triggerEvent('tapauthor', { userId: e.detail?.userId });
        },
        async onLikeTap() {
            if (!this.data.interactiveStats || this.data.likeLoading || this.data.collectLoading) {
                return;
            }
            const item = this.data.item;
            if (!item?.contentId) {
                return;
            }
            const loggedIn = await (0, auth_service_1.ensureLoggedIn)();
            if (!loggedIn) {
                return;
            }
            this.setData({ likeLoading: true });
            const res = await (0, interaction_service_1.setContentLike)(item.contentId, item.liked !== true);
            this.setData({ likeLoading: false });
            if (!res.ok) {
                wx.showToast({ title: res.message.slice(0, 14) || '操作失败', icon: 'none' });
                return;
            }
            this.applyInteractionPatch(applyLikeResult(item, res.data));
        },
        async onCollectTap() {
            if (!this.data.interactiveStats || this.data.likeLoading || this.data.collectLoading) {
                return;
            }
            const item = this.data.item;
            if (!item?.contentId) {
                return;
            }
            const loggedIn = await (0, auth_service_1.ensureLoggedIn)();
            if (!loggedIn) {
                return;
            }
            this.setData({ collectLoading: true });
            const res = await (0, interaction_service_1.setContentCollect)(item.contentId, item.collected !== true);
            this.setData({ collectLoading: false });
            if (!res.ok) {
                wx.showToast({ title: res.message.slice(0, 14) || '操作失败', icon: 'none' });
                return;
            }
            this.applyInteractionPatch(applyCollectResult(item, res.data));
        },
        onCommentTap() {
            if (!this.data.interactiveStats) {
                return;
            }
            const item = this.data.item;
            if (!item?.contentId) {
                return;
            }
            if (item.contentType === content_1.CONTENT_TYPE_LIFE) {
                wx.navigateTo({ url: `${route_1.ROUTES.DETAIL_LIFE}?contentId=${item.contentId}` });
                return;
            }
            if (item.contentType === content_1.CONTENT_TYPE_PROFESSIONAL) {
                wx.navigateTo({ url: `${route_1.ROUTES.DETAIL_PRO}?contentId=${item.contentId}` });
                return;
            }
            wx.showToast({ title: '内容类型异常', icon: 'none' });
        },
        applyInteractionPatch(patch) {
            const item = this.data.item;
            const next = { ...item, ...patch };
            this.setData({
                liked: next.liked === true,
                collected: next.collected === true,
                likeText: (0, format_1.formatCompactNumber)(next.likeCount),
                collectText: (0, format_1.formatCompactNumber)(next.collectCount),
            });
            this.triggerEvent('interactionchange', { item: next });
        },
    },
});
function resolveAuditBadge(raw) {
    const st = typeof raw === 'number' && Number.isFinite(raw) ? Math.floor(raw) : NaN;
    if (st === 0) {
        return { label: '审核中', tone: 'pending' };
    }
    if (st === 1) {
        return { label: '已通过', tone: 'approved' };
    }
    if (st === 2) {
        return { label: '已驳回', tone: 'rejected' };
    }
    return { label: '', tone: '' };
}
function applyLikeResult(item, vo) {
    const liked = vo.isLiked === true;
    const likeCount = vo.likedCount === undefined || vo.likedCount === null
        ? item.likeCount
        : Math.max(0, Number(vo.likedCount) || 0);
    return { liked, likeCount };
}
function applyCollectResult(item, vo) {
    const collected = vo.isCollect === true;
    const collectCount = vo.collectCount === undefined || vo.collectCount === null
        ? item.collectCount
        : Math.max(0, Number(vo.collectCount) || 0);
    return { collected, collectCount };
}
