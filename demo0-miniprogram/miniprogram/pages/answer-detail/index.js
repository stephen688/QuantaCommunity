"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
const answer_service_1 = require("../../services/answer.service");
const content_service_1 = require("../../services/content.service");
const comment_service_1 = require("../../services/comment.service");
const user_service_1 = require("../../services/user.service");
const page_refresh_1 = require("../../utils/page-refresh");
const detail_shared_1 = require("../../utils/detail-shared");
const feed_error_1 = require("../../utils/feed-error");
const report_flow_1 = require("../../utils/report-flow");
const user_profile_nav_1 = require("../../utils/user-profile-nav");
const route_1 = require("../../constants/route");
function detailErrorState(res) {
    const f = (0, feed_error_1.feedFullScreenError)(res);
    return {
        errorType: f.errorType,
        stateTitle: f.stateTitle,
        errorMessage: f.errorMessage,
        stateActionText: f.stateActionText,
    };
}
Page({
    data: {
        answerIdNum: null,
        questionIdNum: null,
        invalidParams: false,
        loading: true,
        errorType: '',
        stateTitle: '',
        errorMessage: '',
        stateActionText: '',
        refreshing: false,
        questionDetail: null,
        pinnedAnswer: null,
        otherAnswers: [],
        comments: [],
        commentLoading: false,
        commentLoadingMore: false,
        commentLoadMoreError: false,
        commentFinished: true,
        commentPageNum: 1,
        composerValue: '',
        composerSubmitting: false,
        replyPlaceholder: '友善评论，文明发言',
        composerVisible: false,
        composerAutoFocus: false,
        replyTargetNick: '',
        answerCommentCount: 0,
        answerLikeLoading: false,
        currentUserId: null,
        isQuestionAuthor: false,
        acceptingAnswerId: null,
    },
    _commentLock: false,
    _replyTarget: null,
    _pageHasShown: false,
    _refreshSnapshot: (0, page_refresh_1.createPageRefreshSnapshot)(),
    _pendingFocusComment: false,
    onLoad(query) {
        const answerId = (0, detail_shared_1.parseDetailContentId)(query.answerId);
        const questionId = (0, detail_shared_1.parseDetailContentId)(query.questionId);
        if (answerId === null || questionId === null) {
            this.setData({ invalidParams: true, loading: false });
            return;
        }
        this._pendingFocusComment = query.focusComment === '1';
        this.setData({
            answerIdNum: answerId,
            questionIdNum: questionId,
            invalidParams: false,
            loading: true,
        });
        void this.syncQuestionAuthor();
        void this.loadPage(false);
    },
    onShow() {
        if (!this._pageHasShown) {
            this._pageHasShown = true;
            return;
        }
        if ((0, page_refresh_1.shouldReloadOnShow)(this._refreshSnapshot, ['userProfile'])) {
            (0, page_refresh_1.markRefreshConsumed)(this._refreshSnapshot, ['userProfile']);
        }
        if (this.data.invalidParams || !this.data.answerIdNum || !this.data.questionIdNum) {
            return;
        }
        if (this.data.loading && !this.data.questionDetail) {
            return;
        }
        void this.syncQuestionAuthor();
        void this.loadPage(true);
    },
    async syncQuestionAuthor() {
        const res = await (0, user_service_1.getUserInfo)();
        if (!res.ok) {
            return;
        }
        const uidRaw = res.data.userId;
        const uid = uidRaw === undefined || uidRaw === null ? NaN : Number(uidRaw);
        const currentUserId = Number.isFinite(uid) && uid > 0 ? uid : null;
        const q = this.data.questionDetail;
        this.setData({
            currentUserId,
            isQuestionAuthor: currentUserId !== null && q !== null && q.authorId === currentUserId,
        });
    },
    refreshQuestionAuthorFlag(question) {
        const uid = this.data.currentUserId;
        this.setData({
            isQuestionAuthor: uid !== null && question !== null && question.authorId === uid,
        });
    },
    onPullDownRefresh() {
        if (this.data.invalidParams || !this.data.answerIdNum || !this.data.questionIdNum) {
            wx.stopPullDownRefresh();
            return;
        }
        void this.refreshAll();
    },
    onReachBottom() {
        if (this.data.commentLoading ||
            this.data.commentLoadingMore ||
            this.data.commentFinished ||
            this.data.commentLoadMoreError) {
            return;
        }
        void this.loadComments(false);
    },
    onInvalidAction() {
        wx.navigateBack({ fail: () => { } });
    },
    onRetryLoad() {
        void this.loadPage(false);
    },
    onQuestionAuthorTap(e) {
        (0, user_profile_nav_1.navigateToUserProfileFromEvent)(e);
    },
    onAnswerAuthorTap(e) {
        (0, user_profile_nav_1.navigateToUserProfileFromEvent)(e);
    },
    onPublishAnswerFabTap() {
        const qid = this.data.questionIdNum;
        const d = this.data.questionDetail;
        if (!qid || !d) {
            return;
        }
        const raw = d.title?.trim() || '';
        const titleParam = raw ? `&title=${encodeURIComponent(raw)}` : '';
        wx.navigateTo({
            url: `${route_1.ROUTES.PUBLISH_ANSWER}?questionId=${qid}${titleParam}`,
            events: {
                published: () => {
                    void this.loadPage(true);
                },
            },
        });
    },
    async refreshAll() {
        this.setData({ refreshing: true });
        try {
            await this.loadPage(true);
        }
        finally {
            this.setData({ refreshing: false });
            wx.stopPullDownRefresh();
        }
    },
    async loadPage(isRefresh) {
        const aid = this.data.answerIdNum;
        const qid = this.data.questionIdNum;
        if (!aid || !qid) {
            return;
        }
        if (!isRefresh) {
            this.setData({
                loading: true,
                errorType: '',
                errorMessage: '',
                stateTitle: '',
                stateActionText: '',
            });
        }
        const [answerRes, questionRes, listRes] = await Promise.all([
            (0, answer_service_1.getAnswerDetail)(aid),
            (0, content_service_1.getContentDetail)(qid),
            (0, answer_service_1.getAnswerList)(qid),
        ]);
        if (!answerRes.ok) {
            const err = detailErrorState(answerRes);
            this.setData({
                loading: false,
                errorType: err.errorType,
                stateTitle: err.stateTitle,
                errorMessage: err.errorMessage,
                stateActionText: err.stateActionText,
            });
            return;
        }
        if (!questionRes.ok) {
            const err = detailErrorState(questionRes);
            this.setData({
                loading: false,
                errorType: err.errorType,
                stateTitle: err.stateTitle,
                errorMessage: err.errorMessage,
                stateActionText: err.stateActionText,
            });
            return;
        }
        if (!listRes.ok) {
            const err = detailErrorState(listRes);
            this.setData({
                loading: false,
                errorType: err.errorType,
                stateTitle: err.stateTitle,
                errorMessage: err.errorMessage,
                stateActionText: err.stateActionText,
            });
            return;
        }
        const detail = (0, content_service_1.mapContentVOToDetail)(questionRes.data);
        const pinned = (0, answer_service_1.mapAnswerVOToModel)(answerRes.data);
        if (!detail || !pinned) {
            const fake = { ok: false, errorType: 'invalidData', message: '数据格式异常' };
            const err = detailErrorState(fake);
            this.setData({
                loading: false,
                errorType: err.errorType,
                stateTitle: err.stateTitle,
                errorMessage: err.errorMessage,
                stateActionText: err.stateActionText,
            });
            return;
        }
        const others = [];
        const all = listRes.data || [];
        for (const row of all) {
            const m = (0, answer_service_1.mapAnswerVOToModel)(row);
            if (!m) {
                continue;
            }
            if (m.answerId === pinned.answerId) {
                continue;
            }
            others.push(m);
        }
        this.setData({
            loading: false,
            errorType: '',
            questionDetail: detail,
            pinnedAnswer: pinned,
            otherAnswers: others,
            answerCommentCount: Math.max(0, pinned.commentCount || 0),
        });
        this.refreshQuestionAuthorFlag(detail);
        await this.runCommentsReset(isRefresh);
        if (this._pendingFocusComment && !isRefresh) {
            this._pendingFocusComment = false;
            this.openComposer();
        }
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
        const pinned = this.data.pinnedAnswer;
        if (pinned) {
            this.setData({
                pinnedAnswer: { ...pinned, isAccepted: pinned.answerId === answerId },
                otherAnswers: this.data.otherAnswers.map((row) => ({
                    ...row,
                    isAccepted: false,
                })),
            });
        }
    },
    runCommentsReset(silent) {
        const qid = this.data.questionIdNum;
        const aid = this.data.answerIdNum;
        if (!qid || !aid) {
            return Promise.resolve();
        }
        if (!silent) {
            this.setData({
                commentPageNum: 1,
                comments: [],
                commentFinished: false,
                commentLoadMoreError: false,
                commentLoading: true,
            });
        }
        else {
            this.setData({
                commentPageNum: 1,
                commentLoading: true,
                commentLoadMoreError: false,
            });
        }
        return (0, comment_service_1.getCommentList)({
            contentId: qid,
            answerId: aid,
            pageNum: 1,
            pageSize: detail_shared_1.DETAIL_COMMENT_PAGE_SIZE,
        }).then((res) => {
            if (!res.ok) {
                if (!silent) {
                    wx.showToast({ title: res.message.slice(0, 14) || '评论加载失败', icon: 'none' });
                    this.setData({ commentLoading: false, comments: [], commentFinished: true });
                }
                else {
                    this.setData({ commentLoading: false });
                }
                return;
            }
            const items = (0, comment_service_1.mapCommentPageToItems)(res.data);
            const finished = (0, detail_shared_1.commentListFinished)(res.data, items.length);
            this.setData({
                comments: items,
                commentLoading: false,
                commentFinished: finished,
                commentPageNum: 1,
            });
            this.syncAnswerCommentCount();
        });
    },
    async loadComments(reset) {
        const qid = this.data.questionIdNum;
        const aid = this.data.answerIdNum;
        if (!qid || !aid || this._commentLock) {
            return;
        }
        if (reset) {
            await this.runCommentsReset(false);
            return;
        }
        if (this.data.commentFinished || this.data.commentLoadingMore) {
            return;
        }
        this._commentLock = true;
        const nextPage = this.data.commentPageNum + 1;
        this.setData({ commentLoadingMore: true, commentLoadMoreError: false });
        const res = await (0, comment_service_1.getCommentList)({
            contentId: qid,
            answerId: aid,
            pageNum: nextPage,
            pageSize: detail_shared_1.DETAIL_COMMENT_PAGE_SIZE,
        });
        this._commentLock = false;
        if (!res.ok) {
            this.setData({ commentLoadingMore: false, commentLoadMoreError: true });
            return;
        }
        const batch = (0, comment_service_1.mapCommentPageToItems)(res.data);
        const merged = this.data.comments.concat(batch);
        const finished = (0, detail_shared_1.commentListFinished)(res.data, batch.length);
        this.setData({
            comments: merged,
            commentPageNum: nextPage,
            commentLoadingMore: false,
            commentFinished: finished || batch.length === 0,
            commentLoadMoreError: false,
        });
    },
    onComposerInput(e) {
        this.setData({ composerValue: e.detail?.value ?? '' });
    },
    async onComposerSubmit(e) {
        const qid = this.data.questionIdNum;
        const aid = this.data.answerIdNum;
        const text = (e.detail?.content ?? this.data.composerValue).trim();
        if (!qid || !aid || !text || this.data.composerSubmitting) {
            return;
        }
        this.setData({ composerSubmitting: true });
        const payload = {
            contentId: qid,
            answerId: aid,
            content: text,
        };
        if (this._replyTarget) {
            payload.parentId = this._replyTarget.parentId;
            payload.replyCommentId = this._replyTarget.replyCommentId;
            payload.replyUserId = this._replyTarget.replyUserId;
        }
        const res = await (0, comment_service_1.sendComment)(payload);
        this.setData({ composerSubmitting: false });
        if (!res.ok) {
            wx.showToast({ title: res.message.slice(0, 14) || '发送失败', icon: 'none' });
            return;
        }
        this._replyTarget = null;
        this.setData({
            composerValue: '',
            replyPlaceholder: '友善评论，文明发言',
            replyTargetNick: '',
            composerVisible: false,
            composerAutoFocus: false,
        });
        wx.showToast({ title: '评论已提交审核', icon: 'success' });
    },
    syncAnswerCommentCount() {
        const pinned = this.data.pinnedAnswer;
        const fromList = this.data.comments.length;
        const count = pinned && pinned.commentCount > fromList ? pinned.commentCount : fromList;
        this.setData({ answerCommentCount: count });
    },
    openComposer() {
        this.setData({ composerVisible: true, composerAutoFocus: false }, () => {
            this.setData({ composerAutoFocus: true });
        });
    },
    closeComposer() {
        this.setData({ composerVisible: false, composerAutoFocus: false });
    },
    onComposerSheetPanelTap() { },
    onCancelReply() {
        this._replyTarget = null;
        this.setData({
            replyPlaceholder: '友善评论，文明发言',
            replyTargetNick: '',
        });
    },
    async onAnswerDetailLike() {
        const aid = this.data.answerIdNum;
        const pinned = this.data.pinnedAnswer;
        if (!aid || !pinned || this.data.answerLikeLoading) {
            return;
        }
        this.setData({ answerLikeLoading: true });
        const res = await (0, answer_service_1.likeAnswer)(aid);
        this.setData({ answerLikeLoading: false });
        if (!res.ok) {
            wx.showToast({ title: res.message.slice(0, 14) || '操作失败', icon: 'none' });
            return;
        }
        const liked = res.data.isLiked === true;
        const lcRaw = res.data.likedCount;
        const likeCount = lcRaw === undefined || lcRaw === null ? undefined : Math.max(0, Number(lcRaw) || 0);
        this.setData({
            pinnedAnswer: {
                ...pinned,
                liked,
                likeCount: likeCount === undefined ? pinned.likeCount : likeCount,
            },
        });
    },
    onCommentRetry() {
        void this.loadComments(false);
    },
    onCommentReply(e) {
        const commentId = e.detail?.commentId;
        const userId = e.detail?.userId;
        const nickName = e.detail?.nickName || '';
        if (!Number.isFinite(commentId) || !commentId || !Number.isFinite(userId) || !userId) {
            return;
        }
        const topLevel = this.findTopLevelParent(commentId);
        const parentId = topLevel?.commentId ?? commentId;
        this._replyTarget = {
            parentId,
            replyCommentId: commentId,
            replyUserId: userId,
        };
        const nick = nickName || '用户';
        this.setData({
            replyPlaceholder: `回复 ${nick}：`,
            replyTargetNick: nick,
        });
        this.openComposer();
    },
    async onCommentLike(e) {
        const id = e.detail?.commentId;
        if (!Number.isFinite(id) || !id) {
            return;
        }
        const res = await (0, comment_service_1.likeComment)(id);
        if (!res.ok) {
            wx.showToast({ title: res.message.slice(0, 14) || '操作失败', icon: 'none' });
            return;
        }
        const liked = res.data.isLiked === true;
        const likeCount = res.data.likedCount === undefined || res.data.likedCount === null
            ? undefined
            : Math.max(0, Number(res.data.likedCount) || 0);
        this.setData({
            comments: (0, detail_shared_1.patchCommentInTree)(this.data.comments, id, (c) => ({
                ...c,
                liked,
                likeCount: likeCount === undefined ? c.likeCount : likeCount,
            })),
        });
    },
    onCommentMore(e) {
        const id = e.detail?.commentId;
        const userId = Number(e.detail?.userId);
        if (!Number.isFinite(id) || !id) {
            return;
        }
        const itemList = (0, detail_shared_1.buildCommentActionSheetItems)(this.data.currentUserId, userId);
        wx.showActionSheet({
            itemList,
            success: (sheet) => {
                const picked = itemList[sheet.tapIndex];
                if (picked === '举报') {
                    void this.runCommentReport(id);
                }
                else if (picked === '删除') {
                    this.confirmDeleteComment(id);
                }
            },
        });
    },
    async runCommentReport(commentId) {
        const rt = await (0, report_flow_1.pickReportType)();
        if (rt === null) {
            return;
        }
        const res = await (0, comment_service_1.reportComment)({ commentId, reportType: rt });
        if (!res.ok) {
            wx.showToast({ title: res.message.slice(0, 18) || '提交失败', icon: 'none' });
            return;
        }
        wx.showToast({ title: '已提交', icon: 'success' });
    },
    confirmDeleteComment(commentId) {
        wx.showModal({
            title: '删除评论',
            content: '删除后不可恢复，确定删除该评论吗？',
            confirmText: '删除',
            confirmColor: detail_shared_1.COMMENT_DELETE_CONFIRM_COLOR,
            success: (modal) => {
                if (!modal.confirm) {
                    return;
                }
                void (async () => {
                    const res = await (0, comment_service_1.deleteComment)(commentId);
                    if (!res.ok) {
                        wx.showToast({ title: res.message.slice(0, 18) || '删除失败', icon: 'none' });
                        return;
                    }
                    wx.showToast({ title: '已删除', icon: 'success' });
                    const { list, depth } = (0, detail_shared_1.removeCommentFromTree)(this.data.comments, commentId);
                    if (depth === null) {
                        return;
                    }
                    this.setData({ comments: list });
                    this.syncAnswerCommentCount();
                })();
            },
        });
    },
    async onCommentLoadReplies(e) {
        const parentId = e.detail?.commentId;
        const qid = this.data.questionIdNum;
        const aid = this.data.answerIdNum;
        if (!Number.isFinite(parentId) || !parentId || !qid || !aid) {
            return;
        }
        const top = this.data.comments.find((row) => row.commentId === parentId);
        if (top?.replyLoading) {
            return;
        }
        if (!(0, detail_shared_1.commentNeedsReplyFetch)(top)) {
            return;
        }
        let comments = (0, detail_shared_1.setCommentReplyLoading)(this.data.comments, parentId, true);
        this.setData({ comments });
        const res = await (0, comment_service_1.getReplyList)({
            parentCommentId: parentId,
            contentId: qid,
            answerId: aid,
            pageNum: 1,
            pageSize: (0, detail_shared_1.replyListPageSize)(top),
        });
        if (!res.ok) {
            comments = (0, detail_shared_1.setCommentReplyError)(comments, parentId, true);
            this.setData({ comments });
            return;
        }
        const list = (0, comment_service_1.mapCommentPageToItems)(res.data);
        comments = (0, detail_shared_1.mergeCommentReplies)(comments, parentId, list);
        this.setData({ comments });
    },
    findTopLevelParent(commentId) {
        for (const row of this.data.comments) {
            if (row.commentId === commentId) {
                return row;
            }
            const replies = row.replies || [];
            if (replies.some((it) => it.commentId === commentId)) {
                return row;
            }
        }
        return null;
    },
});
