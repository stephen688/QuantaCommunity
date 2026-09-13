"use strict";
Component({
    properties: {
        value: {
            type: String,
            value: '',
        },
        placeholder: {
            type: String,
            value: '搜索生活求助、专业问答',
        },
        focus: {
            type: Boolean,
            value: false,
        },
        disabled: {
            type: Boolean,
            value: false,
        },
        showCancel: {
            type: Boolean,
            value: true,
        },
    },
    data: {
        innerValue: '',
        focused: false,
    },
    observers: {
        value(v) {
            const next = typeof v === 'string' ? v : '';
            if (next !== this.data.innerValue) {
                this.setData({ innerValue: next });
            }
        },
    },
    lifetimes: {
        attached() {
            const v = typeof this.data.value === 'string' ? this.data.value : '';
            this.setData({ innerValue: v });
        },
    },
    methods: {
        onInput(e) {
            const v = e.detail?.value ?? '';
            this.setData({ innerValue: v });
            this.triggerEvent('input', { value: v });
        },
        onConfirm(e) {
            const v = (e.detail?.value ?? this.data.innerValue).trim();
            this.triggerEvent('confirm', { value: v });
        },
        onFocus() {
            this.setData({ focused: true });
        },
        onBlur() {
            this.setData({ focused: false });
        },
        onClear() {
            if (this.data.disabled) {
                return;
            }
            this.setData({ innerValue: '' });
            this.triggerEvent('input', { value: '' });
            this.triggerEvent('clear');
        },
        onCancel() {
            this.triggerEvent('cancel');
        },
    },
});
