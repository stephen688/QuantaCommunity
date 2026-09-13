"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
const upload_service_1 = require("../../services/upload.service");
Component({
    properties: {
        /** `grid` 多图网格；`avatar` 单头像圆形 */
        variant: {
            type: String,
            value: 'grid',
        },
        maxCount: {
            type: Number,
            value: 5,
        },
        disabled: {
            type: Boolean,
            value: false,
        },
        /** 初始或受控的已上传 URL 列表；与内部槽位同步时会触发 `change` */
        value: {
            type: Array,
            value: [],
        },
    },
    data: {
        slots: [],
        canAdd: true,
    },
    observers: {
        value(v) {
            this.syncFromParentUrls(v);
        },
        maxCount() {
            this.refreshCanAdd();
        },
        disabled() {
            this.refreshCanAdd();
        },
    },
    lifetimes: {
        attached() {
            const v = Array.isArray(this.data.value) ? this.data.value : [];
            this.syncFromParentUrls(v, true);
        },
    },
    methods: {
        emitState() {
            const slots = this.data.slots;
            const uploading = slots.some((s) => s.status === 'uploading');
            const hasError = slots.some((s) => s.status === 'error');
            this.triggerEvent('statechange', { uploading, hasError });
        },
        syncFromParentUrls(v, force = false) {
            const urls = (Array.isArray(v) ? v : [])
                .map((x) => (typeof x === 'string' ? x.trim() : ''))
                .filter(Boolean);
            const pending = this.data.slots.some((s) => s.status === 'uploading');
            if (pending && !force) {
                return;
            }
            const cur = this.data.slots
                .filter((s) => s.status === 'done' && s.remoteUrl)
                .map((s) => s.remoteUrl);
            if (!force && cur.length === urls.length && cur.every((u, i) => u === urls[i])) {
                return;
            }
            const slots = urls.map((u, i) => makeDoneSlot(u, i));
            this.setData({ slots }, () => {
                this.refreshCanAdd();
                this.emitState();
            });
        },
        refreshCanAdd() {
            const max = Math.min(9, Math.max(1, Number(this.data.maxCount) || 5));
            const n = this.data.slots.length;
            const canAdd = !this.data.disabled && n < max;
            this.setData({ canAdd });
        },
        emitChange() {
            const urls = this.data.slots
                .filter((s) => s.status === 'done' && s.remoteUrl)
                .map((s) => s.remoteUrl);
            this.triggerEvent('change', { value: urls });
        },
        onChoose() {
            if (!this.data.canAdd || this.data.disabled) {
                return;
            }
            const max = Math.min(9, Math.max(1, Number(this.data.maxCount) || 5));
            const remain = max - this.data.slots.length;
            if (remain <= 0) {
                return;
            }
            wx.chooseMedia({
                count: remain,
                mediaType: ['image'],
                sourceType: ['album', 'camera'],
                success: (res) => {
                    const files = res.tempFiles || [];
                    const paths = files
                        .map((f) => f.tempFilePath)
                        .filter((p) => typeof p === 'string' && p);
                    paths.forEach((p) => this.appendAndUpload(p));
                },
            });
        },
        appendAndUpload(tempPath) {
            const id = `s-${Date.now()}-${Math.random().toString(16).slice(2, 8)}`;
            const slot = {
                id,
                tempPath,
                remoteUrl: '',
                status: 'uploading',
                displaySrc: tempPath,
            };
            const slots = [...this.data.slots, slot];
            this.setData({ slots }, () => {
                this.refreshCanAdd();
                this.runUpload(id, tempPath);
                this.emitState();
            });
        },
        runUpload(id, tempPath) {
            void (0, upload_service_1.uploadImage)(tempPath).then((res) => {
                const slots = this.data.slots.map((s) => {
                    if (s.id !== id) {
                        return s;
                    }
                    if (res.ok) {
                        const url = res.data;
                        return {
                            ...s,
                            status: 'done',
                            remoteUrl: url,
                            displaySrc: url,
                        };
                    }
                    return {
                        ...s,
                        status: 'error',
                        displaySrc: s.tempPath,
                    };
                });
                if (!res.ok && res.message) {
                    wx.showToast({ title: res.message.slice(0, 20), icon: 'none' });
                }
                this.setData({ slots }, () => {
                    this.refreshCanAdd();
                    this.emitChange();
                    this.emitState();
                });
            });
        },
        onRetry(e) {
            const id = e.currentTarget?.dataset?.id;
            if (!id || this.data.disabled) {
                return;
            }
            const slots = this.data.slots.map((s) => {
                if (s.id !== id) {
                    return s;
                }
                return {
                    ...s,
                    status: 'uploading',
                    displaySrc: s.tempPath,
                };
            });
            this.setData({ slots }, () => {
                const s = this.data.slots.find((x) => x.id === id);
                if (s) {
                    this.runUpload(id, s.tempPath);
                }
                this.emitState();
            });
        },
        onRemove(e) {
            const id = e.currentTarget?.dataset?.id;
            if (!id || this.data.disabled) {
                return;
            }
            const slots = this.data.slots.filter((s) => s.id !== id);
            this.setData({ slots }, () => {
                this.refreshCanAdd();
                this.emitChange();
                this.emitState();
            });
        },
        onPreview(e) {
            const idx = Number(e.currentTarget?.dataset?.index);
            const slots = this.data.slots;
            const urls = slots.map((s) => s.displaySrc).filter(Boolean);
            if (!urls.length) {
                return;
            }
            const current = Number.isFinite(idx) && idx >= 0 ? urls[idx] : urls[0];
            wx.previewImage({ urls, current });
        },
    },
});
function makeDoneSlot(remoteUrl, index) {
    return {
        id: `sync-${index}`,
        tempPath: remoteUrl,
        remoteUrl,
        status: 'done',
        displaySrc: remoteUrl,
    };
}
