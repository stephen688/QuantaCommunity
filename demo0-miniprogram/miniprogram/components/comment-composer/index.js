"use strict";
Component({
    properties: {
        /** 受控内容；清空时由父级置空字符串 */
        value: {
            type: String,
            value: '',
        },
        placeholder: {
            type: String,
            value: '说点什么…',
        },
        maxlength: {
            type: Number,
            value: 500,
        },
        submitting: {
            type: Boolean,
            value: false,
        },
        disabled: {
            type: Boolean,
            value: false,
        },
        autoFocus: {
            type: Boolean,
            value: false,
        },
        cursorSpacing: {
            type: Number,
            value: 32,
        },
        holdKeyboard: {
            type: Boolean,
            value: true,
        },
    },
    data: {
        draft: '',
        hintText: '',
        sendDisabled: true,
    },
    observers: {
        value(v) {
            const next = typeof v === 'string' ? v : '';
            if (next !== this.data.draft) {
                this.setData({ draft: next });
                this.syncHint(next);
            }
        },
        maxlength() {
            this.syncHint(this.data.draft);
        },
        submitting(s) {
            this.updateSendDisabled(this.data.draft, s);
        },
        disabled() {
            this.updateSendDisabled(this.data.draft, this.data.submitting);
        },
    },
    lifetimes: {
        attached() {
            const v = typeof this.data.value === 'string' ? this.data.value : '';
            this.setData({ draft: v });
            this.syncHint(v);
        },
    },
    methods: {
        onInput(e) {
            const v = e.detail?.value ?? '';
            this.setData({ draft: v });
            this.syncHint(v);
            this.triggerEvent('input', { value: v });
        },
        onSubmitTap() {
            if (this.data.submitting || this.data.disabled) {
                return;
            }
            const text = (this.data.draft || '').trim();
            if (!text) {
                return;
            }
            this.triggerEvent('submit', { content: text });
        },
        syncHint(draft) {
            const max = this.data.maxlength || 500;
            const len = draft.length;
            const hintText = `${len} / ${max}`;
            const empty = !(draft || '').trim();
            const sendDisabled = this.data.submitting || this.data.disabled || empty;
            this.setData({ hintText, sendDisabled });
        },
        updateSendDisabled(draft, submitting) {
            const empty = !(draft || '').trim();
            const sendDisabled = submitting || this.data.disabled || empty;
            this.setData({ sendDisabled });
        },
    },
});
