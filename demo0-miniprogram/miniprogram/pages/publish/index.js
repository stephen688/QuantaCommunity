"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
const content_1 = require("../../constants/content");
const route_1 = require("../../constants/route");
const content_service_1 = require("../../services/content.service");
const publish_1 = require("../../types/publish");
const detail_shared_1 = require("../../utils/detail-shared");
const real_name_service_1 = require("../../services/real-name.service");
function resolveType(raw) {
    return Number(raw) === content_1.CONTENT_TYPE_PROFESSIONAL ? content_1.CONTENT_TYPE_PROFESSIONAL : content_1.CONTENT_TYPE_LIFE;
}
function titlePlaceholderByType(contentType) {
    return contentType === content_1.CONTENT_TYPE_PROFESSIONAL ? '输入专业问题标题' : '说清楚你遇到的问题';
}
Page({
    data: {
        contentType: content_1.CONTENT_TYPE_LIFE,
        title: '',
        content: '',
        images: [],
        submitting: false,
        uploading: false,
        hasUploadError: false,
        errorMessage: '',
        titleCount: 0,
        contentCount: 0,
        titlePlaceholder: titlePlaceholderByType(content_1.CONTENT_TYPE_LIFE),
        keyboardInset: 0,
    },
    onKeyboardHeightChange(res) {
        const h = Math.max(0, res.height || 0);
        if (h !== this.data.keyboardInset) {
            this.setData({ keyboardInset: h });
        }
    },
    onLoad(query) {
        const nextType = resolveType(query.contentType);
        this.setData({
            contentType: nextType,
            titlePlaceholder: titlePlaceholderByType(nextType),
        });
    },
    onTypeTap(e) {
        const nextType = resolveType(e.currentTarget?.dataset?.type ?? '');
        if (nextType === this.data.contentType || this.data.submitting || this.data.uploading) {
            return;
        }
        this.setData({
            contentType: nextType,
            titlePlaceholder: titlePlaceholderByType(nextType),
            errorMessage: '',
        });
    },
    onTitleInput(e) {
        const value = e.detail?.value ?? '';
        this.setData({
            title: value,
            titleCount: value.length,
            errorMessage: '',
        });
    },
    onContentInput(e) {
        const value = e.detail?.value ?? '';
        this.setData({
            content: value,
            contentCount: value.length,
            errorMessage: '',
        });
    },
    onImagesChange(e) {
        const raw = e.detail?.value;
        const list = Array.isArray(raw) ? raw.filter((x) => typeof x === 'string') : [];
        this.setData({
            images: list.slice(0, publish_1.PUBLISH_IMAGE_MAX_COUNT),
            errorMessage: '',
        });
    },
    onUploadStateChange(e) {
        this.setData({
            uploading: e.detail?.uploading === true,
            hasUploadError: e.detail?.hasError === true,
        });
    },
    validateForm() {
        const title = this.data.title.trim();
        const body = this.data.content.trim();
        if (!title) {
            return '请填写标题';
        }
        if (title.length > publish_1.PUBLISH_TITLE_MAX_LEN) {
            return `标题最多 ${publish_1.PUBLISH_TITLE_MAX_LEN} 字`;
        }
        if (!body) {
            return '请填写正文';
        }
        if (body.length > publish_1.PUBLISH_BODY_MAX_LEN) {
            return `正文最多 ${publish_1.PUBLISH_BODY_MAX_LEN} 字`;
        }
        if (this.data.images.length > publish_1.PUBLISH_IMAGE_MAX_COUNT) {
            return `最多上传 ${publish_1.PUBLISH_IMAGE_MAX_COUNT} 张图片`;
        }
        if (this.data.uploading) {
            return '图片上传中，请稍候';
        }
        if (this.data.hasUploadError) {
            return '存在上传失败图片，请重试或删除后再发布';
        }
        return '';
    },
    async onSubmit() {
        if (this.data.submitting) {
            return;
        }
        const verified = await (0, real_name_service_1.ensureRealNameVerified)({
            reason: '发布内容',
            description: '发布帖子需先完成校友实名认证，审核通过后即可发布。',
        });
        if (!verified) {
            return;
        }
        const error = this.validateForm();
        if (error) {
            this.setData({ errorMessage: error });
            wx.showToast({ title: error.slice(0, 14), icon: 'none' });
            return;
        }
        const payload = {
            contentType: this.data.contentType,
            title: this.data.title.trim(),
            content: this.data.content.trim(),
            images: this.data.images.slice(0, publish_1.PUBLISH_IMAGE_MAX_COUNT),
        };
        this.setData({ submitting: true, errorMessage: '' });
        const res = await (0, content_service_1.publishContent)(payload);
        this.setData({ submitting: false });
        if (!res.ok) {
            let msg = res.message || '发布失败，请稍后重试';
            if (res.errorType === 'unauthorized') {
                msg = '登录状态失效，请重新登录';
            }
            else if ((0, detail_shared_1.isRealNameRequiredMessage)(msg)) {
                msg = '请先完成实名认证';
            }
            this.setData({ errorMessage: msg });
            wx.showToast({ title: msg.slice(0, 14), icon: 'none' });
            return;
        }
        const contentId = Number(res.data.contentId);
        if (!Number.isFinite(contentId) || contentId <= 0) {
            wx.showToast({ title: '已提交审核', icon: 'success' });
            wx.navigateBack({ fail: () => { } });
            return;
        }
        wx.showToast({ title: '已提交审核，通过后展示', icon: 'success' });
        setTimeout(() => {
            wx.redirectTo({ url: `${route_1.ROUTES.MY_CONTENT}?audit=0` });
        }, 200);
    },
});
