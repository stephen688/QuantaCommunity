"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
const answer_service_1 = require("../../services/answer.service");
const content_service_1 = require("../../services/content.service");
const detail_shared_1 = require("../../utils/detail-shared");
const MAX_LEN = 2000;
const COUNT_WARN_AT = 1840;
function decodeTitle(raw) {
    if (!raw || typeof raw !== 'string') {
        return '';
    }
    try {
        return decodeURIComponent(raw).trim();
    }
    catch {
        return raw.trim();
    }
}
Page({
    data: {
        invalid: false,
        questionId: null,
        questionTitle: '',
        questionMeta: '',
        body: '',
        submitting: false,
        loadingSummary: false,
        summaryError: '',
        maxLen: MAX_LEN,
        submitDisabled: true,
        countWarning: false,
    },
    onLoad(query) {
        const qid = (0, detail_shared_1.parseDetailContentId)(query.questionId);
        if (qid === null) {
            this.setData({ invalid: true });
            return;
        }
        const fromQuery = decodeTitle(query.title);
        this.setData({
            invalid: false,
            questionId: qid,
            questionTitle: fromQuery,
            questionMeta: '',
            summaryError: '',
            loadingSummary: !fromQuery,
        });
        if (!fromQuery) {
            void this.loadQuestionTitle(qid);
        }
        else {
            void this.enrichQuestionMeta(qid);
        }
    },
    onInvalidBack() {
        wx.navigateBack({ fail: () => { } });
    },
    onRetrySummary() {
        const qid = this.data.questionId;
        if (!qid) {
            return;
        }
        void this.loadQuestionTitle(qid);
    },
    buildQuestionMeta(mapped) {
        if (!mapped) {
            return '';
        }
        const parts = [mapped.quantaDepartment, mapped.quantaBatch].filter((s) => typeof s === 'string' && !!s.trim());
        return parts.join(' · ');
    },
    async enrichQuestionMeta(questionId) {
        const res = await (0, content_service_1.getContentDetail)(questionId);
        if (!res.ok) {
            return;
        }
        const mapped = (0, content_service_1.mapContentVOToDetail)(res.data);
        this.setData({ questionMeta: this.buildQuestionMeta(mapped) });
    },
    async loadQuestionTitle(questionId) {
        this.setData({ loadingSummary: true, summaryError: '' });
        const res = await (0, content_service_1.getContentDetail)(questionId);
        this.setData({ loadingSummary: false });
        if (!res.ok) {
            this.setData({ summaryError: res.message || '标题加载失败' });
            return;
        }
        const mapped = (0, content_service_1.mapContentVOToDetail)(res.data);
        const title = mapped?.title?.trim() || '';
        this.setData({
            questionTitle: title,
            questionMeta: this.buildQuestionMeta(mapped),
        });
    },
    onBodyInput(e) {
        const v = e.detail?.value ?? '';
        this.setData({
            body: v,
            countWarning: v.length >= COUNT_WARN_AT,
            submitDisabled: !v.trim() || this.data.submitting,
        });
    },
    async onSubmit() {
        const qid = this.data.questionId;
        const text = this.data.body.trim();
        if (!qid || !text || this.data.submitting || this.data.loadingSummary) {
            if (!text) {
                wx.showToast({ title: '请输入回答内容', icon: 'none' });
            }
            return;
        }
        this.setData({ submitting: true, submitDisabled: true });
        const res = await (0, answer_service_1.publishAnswer)({ questionId: qid, content: text });
        this.setData({
            submitting: false,
            submitDisabled: !this.data.body.trim(),
            countWarning: this.data.body.length >= COUNT_WARN_AT,
        });
        if (!res.ok) {
            wx.showToast({ title: res.message.slice(0, 18) || '发布失败', icon: 'none' });
            return;
        }
        wx.showToast({ title: '已提交审核，通过后展示', icon: 'success' });
        const self = this;
        const ec = typeof self.getOpenerEventChannel === 'function' ? self.getOpenerEventChannel() : null;
        if (ec && typeof ec.emit === 'function') {
            ec.emit('published', { answerId: res.data.answerId });
        }
        setTimeout(() => {
            wx.navigateBack({ fail: () => { } });
        }, 400);
    },
});
