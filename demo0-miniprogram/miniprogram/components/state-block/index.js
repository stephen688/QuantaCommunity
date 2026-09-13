"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
const ERROR_TYPES = [
    'network',
    'unauthorized',
    'server',
    'invalidData',
    'unknown',
];
function resolveGraphic(type, variant) {
    if (variant && variant !== 'auto' && variant !== 'default') {
        return variant;
    }
    if (ERROR_TYPES.includes(type)) {
        return 'network';
    }
    if (type === 'empty') {
        return 'list';
    }
    return 'default';
}
Component({
    properties: {
        type: {
            type: String,
            value: 'empty',
        },
        /** auto | default | bulletin | envelope | list | network | follow | search | collect | message */
        variant: {
            type: String,
            value: 'auto',
        },
        title: {
            type: String,
            value: '',
        },
        description: {
            type: String,
            value: '',
        },
        actionText: {
            type: String,
            value: '',
        },
    },
    data: {
        showAction: false,
        graphic: 'list',
    },
    observers: {
        actionText(t) {
            this.setData({ showAction: Boolean(t && t.trim()) });
        },
        'type, variant'(type, variant) {
            this.setData({ graphic: resolveGraphic(type, variant) });
        },
    },
    lifetimes: {
        attached() {
            const t = this.data.actionText;
            this.setData({
                showAction: Boolean(t && t.trim()),
                graphic: resolveGraphic(this.data.type, this.data.variant),
            });
        },
    },
    methods: {
        onActionTap() {
            this.triggerEvent('action', { type: this.data.type });
        },
    },
});
