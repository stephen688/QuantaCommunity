"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
const content_1 = require("../../constants/content");
const content_service_1 = require("../../services/content.service");
const answer_service_1 = require("../../services/answer.service");
const interaction_service_1 = require("../../services/interaction.service");
const user_service_1 = require("../../services/user.service");
const real_name_service_1 = require("../../services/real-name.service");
const detail_shared_1 = require("../../utils/detail-shared");
const feed_error_1 = require("../../utils/feed-error");
const report_flow_1 = require("../../utils/report-flow");
const route_1 = require("../../constants/route");
const page_refresh_1 = require("../../utils/page-refresh");
const user_profile_nav_1 = require("../../utils/user-profile-nav");
function detailErrorState(res) {
    if (res.errorType === 'server' && (0, detail_shared_1.isRealNameRequiredMessage)(res.message)) {
        return {
            errorType: 'server',
            stateTitle: '需要完成实名认证',
            errorMessage: res.message || '请先完成实名认证后再查看内容',
            stateActionText: '重新加载',
        };
    }
    const f = (0, feed_error_1.feedFullScreenError)(res);
    return {
        errorType: f.errorType,
        stateTitle: f.stateTitle,
        errorMessage: f.errorMessage,
        stateActionText: f.stateActionText,
    };
}
function mapAnswers(list) {
    if (!Array.isArray(list)) {
        return [];
    }
    const out = [];
    for (const vo of list) {
        const m = (0, answer_service_1.mapAnswerVOToModel)(vo);
        if (m) {
            out.push(m);
        }
    }
    return out;
}
Page({
    data: {
        contentIdNum: null,
        detail: null,
        typeMismatch: false,
        loading: true,
        refreshing: false,
        errorType: '',
        errorMessage: '',
        stateTitle: '',
        stateActionText: '',
        invalidId: false,
        likeLoading: false,
        collectLoading: false,
        answers: [],
        answerLoading: false,
        answerError: false,
        contentDeleted: false,
        currentUserId: null,
        isQuestionAuthor: false,
        acceptingAnswerId: null,
    },
    _answerLikeInFlight: new Set(),
    _pageHasShown: false,
    _refreshSnapshot: (0, page_refresh_1.createPageRefreshSnapshot)(),
    onLoad(query) {
        const id = (0, detail_shared_1.parseDetailContentId)(query.contentId);
        if (id === null) {
            this.setData({
                invalidId: true,
                loading: false,
                contentIdNum: null,
            });
            return;
        }
        this.setData({ contentIdNum: id, invalidId: false, contentDeleted: false });
        void this.syncQuestionAuthor();
        void this.loadDetail(false);
    },
    onShow() {
        if ((0, page_refresh_1.handleDetailProfileRefreshOnShow)(this, () => this.applyProfileRefresh())) {
            return;
        }
        if (this.data.invalidId ||
            this.data.typeMismatch ||
            !this.data.detail ||
            this.data.loading) {
            return;
        }
        void this.loadAnswers(true);
    },
    async applyProfileRefresh() {
        await this.syncQuestionAuthor();
        const uid = this.data.currentUserId;
        const authorId = this.data.detail?.authorId;
        if (this.data.detail &&
            !this.data.typeMismatch &&
            uid !== null &&
            authorId !== undefined &&
            authorId === uid) {
            void this.loadDetail(false);
        }
        if (this.data.detail && !this.data.typeMismatch && !this.data.invalidId) {
            void this.loadAnswers(true);
        }
    },
    async syncQuestionAuthor() {
        const res = await (0, user_service_1.getUserInfo)();
        if (!res.ok) {
            return;
        }
        const uidRaw = res.data.userId;
        const uid = uidRaw === undefined || uidRaw === null ? NaN : Number(uidRaw);
        const currentUserId = Number.isFinite(uid) && uid > 0 ? uid : null;
        const detail = this.data.detail;
        this.setData({
            currentUserId,
            isQuestionAuthor: currentUserId !== null && detail !== null && detail.authorId === currentUserId,
        });
    },
    refreshQuestionAuthorFlag(detail) {
        const uid = this.data.currentUserId;
        this.setData({
            isQuestionAuthor: uid !== null && detail !== null && detail.authorId === uid,
        });
    },
    applyAcceptedAnswer(answerId) {
        const list = this.data.answers.map((row) => ({
            ...row,
            isAccepted: row.answerId === answerId,
        }));
        this.setData({ answers: list });
    },
    onPullDownRefresh() {
        if (this.data.invalidId || !this.data.contentIdNum) {
            wx.stopPullDownRefresh();
            return;
        }
        void this.refreshAll();
    },
    onDetailStateAction() {
        if (this.data.invalidId) {
            wx.navigateBack({ fail: () => { } });
            return;
        }
        void this.loadDetail(false);
    },
    onTypeMismatchAction() {
        wx.navigateBack({ fail: () => { } });
    },
    onPreviewImage(e) {
        const url = e.currentTarget?.dataset?.url;
        const urls = this.data.detail?.images;
        if (typeof url !== 'string' || !Array.isArray(urls) || urls.length === 0) {
            return;
        }
        wx.previewImage({ current: url, urls });
    },
    onAuthorTap(e) {
        (0, user_profile_nav_1.navigateToUserProfileFromEvent)(e);
    },
    onContentMore() {
        const cid = this.data.contentIdNum;
        if (!cid || !this.data.detail || this.data.typeMismatch) {
            return;
        }
        wx.showActionSheet({
            itemList: ['举报内容', '删除内容'],
            success: (sheet) => {
                if (sheet.tapIndex === 0) {
                    void this.runContentReport(cid);
                }
                else if (sheet.tapIndex === 1) {
                    this.confirmDeleteContent(cid);
                }
            },
        });
    },
    async runContentReport(contentId) {
        const rt = await (0, report_flow_1.pickReportType)();
        if (rt === null) {
            return;
        }
        const res = await (0, content_service_1.reportContent)({ contentId, reportType: rt });
        if (!res.ok) {
            wx.showToast({ title: res.message.slice(0, 18) || '提交失败', icon: 'none' });
            return;
        }
        wx.showToast({ title: '已提交', icon: 'success' });
    },
    confirmDeleteContent(contentId) {
        wx.showModal({
            title: '删除内容',
            content: '删除后不可恢复，确定删除该内容吗？',
            confirmText: '删除',
            confirmColor: '#e54d42',
            success: (modal) => {
                if (!modal.confirm) {
                    return;
                }
                void (async () => {
                    const res = await (0, content_service_1.deleteContent)(contentId);
                    if (!res.ok) {
                        wx.showToast({ title: res.message.slice(0, 18) || '删除失败', icon: 'none' });
                        return;
                    }
                    wx.showToast({ title: '已删除', icon: 'success' });
                    this.setData({
                        detail: null,
                        answers: [],
                        answerLoading: false,
                    });
                    wx.navigateBack({
                        fail: () => {
                            this.setData({ contentDeleted: true });
                        },
                    });
                })();
            },
        });
    },
    onContentDeletedBack() {
        wx.switchTab({ url: route_1.ROUTES.HOME });
    },
    onAnswerAuthorTap(e) {
        (0, user_profile_nav_1.navigateToUserProfileFromEvent)(e);
    },
    async refreshAll() {
        this.setData({ refreshing: true });
        try {
            await this.loadDetail(true);
            const ok = this.data.detail &&
                this.data.detail.contentType === content_1.CONTENT_TYPE_PROFESSIONAL &&
                !this.data.typeMismatch;
            if (ok) {
                await this.loadAnswers(true);
            }
        }
        catch {
            wx.showToast({ title: '刷新失败', icon: 'none' });
        }
        finally {
            this.setData({ refreshing: false });
            wx.stopPullDownRefresh();
        }
    },
    async loadAnswers(silent) {
        const qid = this.data.contentIdNum;
        if (!qid || this.data.typeMismatch) {
            return;
        }
        if (!silent) {
            this.setData({ answerLoading: true, answerError: false, answers: [] });
        }
        else {
            this.setData({ answerLoading: true, answerError: false });
        }
        const res = await (0, answer_service_1.getAnswerList)(qid);
        if (!res.ok) {
            this.setData({
                answerLoading: false,
                answerError: true,
                answers: silent ? this.data.answers : [],
            });
            if (!silent) {
                wx.showToast({ title: res.message.slice(0, 14) || '回答加载失败', icon: 'none' });
            }
            return;
        }
        this.setData({
            answers: mapAnswers(res.data),
            answerLoading: false,
            answerError: false,
        });
    },
    async loadDetail(isRefresh) {
        const cid = this.data.contentIdNum;
        if (!cid) {
            return;
        }
        if (!isRefresh) {
            this.setData({
                loading: true,
                errorType: '',
                errorMessage: '',
                stateTitle: '',
                stateActionText: '',
                typeMismatch: false,
            });
        }
        const res = await (0, content_service_1.getContentDetail)(cid);
        if (!res.ok) {
            if (isRefresh && this.data.detail) {
                wx.showToast({ title: res.message.slice(0, 14) || '加载失败', icon: 'none' });
                this.setData({ loading: false });
                return;
            }
            const err = detailErrorState(res);
            this.setData({
                loading: false,
                detail: null,
                typeMismatch: false,
                errorType: err.errorType,
                errorMessage: err.errorMessage,
                stateTitle: err.stateTitle,
                stateActionText: err.stateActionText,
            });
            return;
        }
        const mapped = (0, content_service_1.mapContentVOToDetail)(res.data);
        if (!mapped) {
            const fake = { ok: false, errorType: 'invalidData', message: '内容数据异常' };
            const err = detailErrorState(fake);
            this.setData({
                loading: false,
                detail: null,
                errorType: err.errorType,
                stateTitle: err.stateTitle,
                errorMessage: err.errorMessage,
                stateActionText: err.stateActionText,
            });
            return;
        }
        if (mapped.contentType !== content_1.CONTENT_TYPE_PROFESSIONAL) {
            this.setData({
                loading: false,
                detail: mapped,
                typeMismatch: true,
                errorType: '',
            });
            return;
        }
        this.setData({
            loading: false,
            detail: mapped,
            typeMismatch: false,
            errorType: '',
        });
        this.refreshQuestionAuthorFlag(mapped);
        if (!isRefresh) {
            await this.loadAnswers(false);
        }
    },
    async onCommentEntryTap() {
        const verified = await (0, real_name_service_1.ensureRealNameVerified)({
            reason: '发表评论',
            description: '发表评论需先完成校友实名认证，审核通过后即可互动。',
        });
        if (!verified) {
            return;
        }
        this.promptCommentOnAnswer();
    },
    onAnswerCommentTap(e) {
        const raw = e.currentTarget?.dataset?.answerId;
        const aid = typeof raw === 'number' ? raw : Number(raw);
        if (!Number.isFinite(aid) || aid <= 0) {
            return;
        }
        this.navigateToAnswerDetail(aid, true);
    },
    promptCommentOnAnswer() {
        const answers = this.data.answers;
        if (answers.length === 0) {
            wx.showToast({ title: '暂无回答，无法评论', icon: 'none' });
            return;
        }
        if (answers.length === 1) {
            this.navigateToAnswerDetail(answers[0].answerId, true);
            return;
        }
        const itemList = answers.map((row, index) => {
            const name = row.authorName?.trim() || `回答 ${index + 1}`;
            const preview = row.content?.trim().slice(0, 14) || '';
            if (!preview) {
                return name;
            }
            const suffix = row.content && row.content.length > 14 ? '…' : '';
            return `${name}：${preview}${suffix}`;
        });
        wx.showActionSheet({
            itemList,
            success: (sheet) => {
                const row = answers[sheet.tapIndex];
                if (row) {
                    this.navigateToAnswerDetail(row.answerId, true);
                }
            },
        });
    },
    onPublishAnswerFabTap() {
        const qid = this.data.contentIdNum;
        const d = this.data.detail;
        if (!qid || !d || this.data.typeMismatch) {
            return;
        }
        const raw = d.title?.trim() || '';
        const titleParam = raw ? `&title=${encodeURIComponent(raw)}` : '';
        wx.navigateTo({
            url: `${route_1.ROUTES.PUBLISH_ANSWER}?questionId=${qid}${titleParam}`,
            events: {
                published: () => {
                    void this.loadAnswers(false);
                },
            },
        });
    },
    async onDetailLike() {
        const cid = this.data.contentIdNum;
        const d = this.data.detail;
        if (!cid || !d || this.data.likeLoading || this.data.collectLoading) {
            return;
        }
        this.setData({ likeLoading: true });
        const res = await (0, interaction_service_1.setContentLike)(cid, d.liked !== true);
        this.setData({ likeLoading: false });
        if (!res.ok) {
            wx.showToast({ title: res.message.slice(0, 14) || '操作失败', icon: 'none' });
            return;
        }
        const patch = (0, detail_shared_1.applyLikeResult)(d, res.data);
        this.setData({ detail: { ...d, ...patch } });
    },
    async onDetailCollect() {
        const cid = this.data.contentIdNum;
        const d = this.data.detail;
        if (!cid || !d || this.data.likeLoading || this.data.collectLoading) {
            return;
        }
        this.setData({ collectLoading: true });
        const res = await (0, interaction_service_1.setContentCollect)(cid, d.collected !== true);
        this.setData({ collectLoading: false });
        if (!res.ok) {
            wx.showToast({ title: res.message.slice(0, 14) || '操作失败', icon: 'none' });
            return;
        }
        const patch = (0, detail_shared_1.applyCollectResult)(d, res.data);
        this.setData({ detail: { ...d, ...patch } });
    },
    onAnswerRetry() {
        void this.loadAnswers(false);
    },
    async onAnswerLike(e) {
        const raw = e.currentTarget?.dataset?.answerId;
        const aid = typeof raw === 'number' ? raw : Number(raw);
        if (!Number.isFinite(aid) || aid <= 0) {
            return;
        }
        const locks = this._answerLikeInFlight;
        if (locks.has(aid)) {
            return;
        }
        locks.add(aid);
        const res = await (0, answer_service_1.likeAnswer)(aid);
        locks.delete(aid);
        if (!res.ok) {
            wx.showToast({ title: res.message.slice(0, 18) || '操作失败', icon: 'none' });
            return;
        }
        const liked = res.data.isLiked === true;
        const lcRaw = res.data.likedCount;
        const likeCount = lcRaw === undefined || lcRaw === null ? undefined : Math.max(0, Number(lcRaw) || 0);
        const list = this.data.answers.map((row) => {
            if (row.answerId !== aid) {
                return row;
            }
            return {
                ...row,
                liked,
                likeCount: likeCount === undefined ? row.likeCount : likeCount,
            };
        });
        this.setData({ answers: list });
    },
    onAnswerMore(e) {
        const raw = e.currentTarget?.dataset?.answerId;
        const aid = typeof raw === 'number' ? raw : Number(raw);
        if (!Number.isFinite(aid) || aid <= 0) {
            return;
        }
        const row = this.data.answers.find((a) => a.answerId === aid);
        const items = [];
        if (this.data.isQuestionAuthor && row && !row.isAccepted) {
            items.push('采纳此回答');
        }
        items.push('删除回答');
        wx.showActionSheet({
            itemList: items,
            success: (sheet) => {
                const picked = items[sheet.tapIndex];
                if (picked === '采纳此回答') {
                    this.confirmAcceptAnswer(aid);
                }
                else if (picked === '删除回答') {
                    this.confirmDeleteAnswer(aid);
                }
            },
        });
    },
    onAcceptAnswerTap(e) {
        const raw = e.currentTarget?.dataset?.answerId;
        const aid = typeof raw === 'number' ? raw : Number(raw);
        if (!Number.isFinite(aid) || aid <= 0 || !this.data.isQuestionAuthor) {
            return;
        }
        this.confirmAcceptAnswer(aid);
    },
    confirmAcceptAnswer(answerId) {
        if (!this.data.isQuestionAuthor || this.data.acceptingAnswerId !== null) {
            return;
        }
        wx.showModal({
            title: '采纳回答',
            content: '采纳后将突出展示该回答，确定采纳吗？',
            confirmText: '采纳',
            success: (modal) => {
                if (!modal.confirm) {
                    return;
                }
                void this.runAcceptAnswer(answerId);
            },
        });
    },
    async runAcceptAnswer(answerId) {
        this.setData({ acceptingAnswerId: answerId });
        const res = await (0, answer_service_1.acceptAnswer)(answerId);
        this.setData({ acceptingAnswerId: null });
        if (!res.ok) {
            wx.showToast({ title: res.message.slice(0, 18) || '操作失败，请重试', icon: 'none' });
            return;
        }
        wx.showToast({ title: '已采纳', icon: 'success' });
        this.applyAcceptedAnswer(answerId);
    },
    confirmDeleteAnswer(answerId) {
        wx.showModal({
            title: '删除回答',
            content: '删除后不可恢复，确定删除该回答吗？',
            confirmText: '删除',
            confirmColor: '#e54d42',
            success: (modal) => {
                if (!modal.confirm) {
                    return;
                }
                void (async () => {
                    const res = await (0, answer_service_1.deleteAnswer)(answerId);
                    if (!res.ok) {
                        wx.showToast({ title: res.message.slice(0, 18) || '删除失败', icon: 'none' });
                        return;
                    }
                    wx.showToast({ title: '已删除', icon: 'success' });
                    await this.loadAnswers(true);
                })();
            },
        });
    },
    onAnswerCardTap(e) {
        const raw = e.currentTarget?.dataset?.answerId;
        const aid = typeof raw === 'number' ? raw : Number(raw);
        if (!Number.isFinite(aid) || aid <= 0) {
            return;
        }
        this.navigateToAnswerDetail(aid);
    },
    navigateToAnswerDetail(answerId, focusComment = false) {
        const qid = this.data.contentIdNum;
        if (!qid) {
            return;
        }
        const focus = focusComment ? '&focusComment=1' : '';
        wx.navigateTo({
            url: `${route_1.ROUTES.ANSWER_DETAIL}?answerId=${answerId}&questionId=${qid}${focus}`,
        });
    },
});
