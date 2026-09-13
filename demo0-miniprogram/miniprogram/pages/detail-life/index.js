"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
const content_1 = require("../../constants/content");
const content_service_1 = require("../../services/content.service");
const comment_service_1 = require("../../services/comment.service");
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
        contentDeleted: false,
        currentUserId: null,
    },
    _commentLock: false,
    _replyTarget: null,
    _refreshSnapshot: (0, page_refresh_1.createPageRefreshSnapshot)(),
    onLoad(query) {
        const id = (0, detail_shared_1.parseDetailContentId)(query.contentId);
        if (id === null) {
            this.setData({
                invalidId: true,
                loading: false,
                contentIdNum: null,
                errorType: '',
            });
            return;
        }
        this.setData({ contentIdNum: id, invalidId: false, contentDeleted: false });
        void this.syncCurrentUser();
        void this.loadDetail(false);
    },
    onShow() {
        (0, page_refresh_1.handleDetailProfileRefreshOnShow)(this, () => this.applyProfileRefresh());
    },
    async applyProfileRefresh() {
        await this.syncCurrentUser();
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
            void this.loadComments(true);
        }
    },
    async syncCurrentUser() {
        const res = await (0, user_service_1.getUserInfo)();
        if (!res.ok) {
            return;
        }
        const uidRaw = res.data.userId;
        const uid = uidRaw === undefined || uidRaw === null ? NaN : Number(uidRaw);
        const currentUserId = Number.isFinite(uid) && uid > 0 ? uid : null;
        this.setData({ currentUserId });
    },
    onPullDownRefresh() {
        if (this.data.invalidId || !this.data.contentIdNum) {
            wx.stopPullDownRefresh();
            return;
        }
        void this.refreshAll();
    },
    onReachBottom() {
        if (this.data.typeMismatch ||
            !this.data.detail ||
            this.data.detail.contentType !== content_1.CONTENT_TYPE_LIFE ||
            this.data.commentLoading ||
            this.data.commentLoadingMore ||
            this.data.commentFinished ||
            this.data.commentLoadMoreError) {
            return;
        }
        void this.loadComments(false);
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
                        comments: [],
                        commentFinished: true,
                        commentLoading: false,
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
                    const d = this.data.detail;
                    const patch = { comments: list };
                    if (depth === 0 && d) {
                        patch.detail = { ...d, commentCount: Math.max(0, d.commentCount - 1) };
                    }
                    this.setData(patch);
                })();
            },
        });
    },
    async refreshAll() {
        this.setData({ refreshing: true });
        try {
            await this.loadDetail(true);
            if (this.data.detail && this.data.detail.contentType === content_1.CONTENT_TYPE_LIFE && !this.data.typeMismatch) {
                await this.runCommentsReset(true);
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
    runCommentsReset(silent) {
        const cid = this.data.contentIdNum;
        if (!cid || this.data.typeMismatch) {
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
            contentId: cid,
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
        if (mapped.contentType !== content_1.CONTENT_TYPE_LIFE) {
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
        if (!isRefresh) {
            await this.runCommentsReset(false);
        }
    },
    async loadComments(reset) {
        const cid = this.data.contentIdNum;
        if (!cid || this.data.typeMismatch || this._commentLock) {
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
            contentId: cid,
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
        const cid = this.data.contentIdNum;
        const text = (e.detail?.content ?? this.data.composerValue).trim();
        if (!cid || !text || this.data.composerSubmitting) {
            return;
        }
        const verified = await (0, real_name_service_1.ensureRealNameVerified)({
            reason: '发表评论',
            description: '发表评论需先完成校友实名认证，审核通过后即可互动。',
        });
        if (!verified) {
            return;
        }
        this.setData({ composerSubmitting: true });
        const payload = { contentId: cid, content: text };
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
    async onCommentLoadReplies(e) {
        const parentId = e.detail?.commentId;
        const cid = this.data.contentIdNum;
        if (!Number.isFinite(parentId) || !parentId || !cid) {
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
            contentId: cid,
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
